"""DontFall vision on the PC (docs/camera-plan.md step 4).

Subscribes to the board's camera once, finds people with YOLOX and faces
with YuNet, and publishes:
  /vision/image/compressed  the frame it looked at, unchanged, so Foxglove
                            (and anything else on the PC) shows it without
                            a second copy over WiFi
  /vision/people            vision_msgs/Detection2DArray, stamped with the
                            frame's capture time
  /vision/faces             the same for faces
  /vision/annotations       foxglove_msgs/ImageAnnotations: the boxes, for
                            Foxglove's Image panel
It only publishes what it sees; it never commands the robot.
"""
import time

import cv2
import numpy as np
import rclpy
from foxglove_msgs.msg import Color, ImageAnnotations, Point2, PointsAnnotation, TextAnnotation
from rclpy.node import Node
from rclpy.qos import HistoryPolicy, QoSProfile, ReliabilityPolicy
from rclpy.signals import SignalHandlerOptions
from sensor_msgs.msg import CompressedImage
from vision_msgs.msg import Detection2D, Detection2DArray, ObjectHypothesisWithPose

from yolox import PersonDetector
from yunet import FaceDetector

PERSON_COLOR = Color(r=0.2, g=0.9, b=0.4, a=1.0)
FACE_COLOR = Color(r=1.0, g=0.6, b=0.1, a=1.0)
TEXT_BG = Color(r=0.0, g=0.0, b=0.0, a=0.6)
WHITE = Color(r=1.0, g=1.0, b=1.0, a=1.0)


class VisionNode(Node):
    def __init__(self):
        super().__init__('dontfall_vision')
        person_model = self.declare_parameter(
            'person_model', '/opt/models/yolox_tiny.onnx').value
        face_model = self.declare_parameter(
            'face_model', '/opt/models/face_detection_yunet_2023mar.onnx').value
        self.people = PersonDetector(
            person_model, self.declare_parameter('person_threshold', 0.4).value)
        self.faces = FaceDetector(
            face_model, self.declare_parameter('face_threshold', 0.6).value)

        # Only the newest frame: when a detection is slower than the camera,
        # older frames are skipped, never queued (a late box is a wrong box).
        latest = QoSProfile(depth=1, reliability=ReliabilityPolicy.BEST_EFFORT,
                            history=HistoryPolicy.KEEP_LAST)
        self.create_subscription(CompressedImage, '/camera/image_raw/compressed',
                                 self.on_image, latest)
        self.image_pub = self.create_publisher(CompressedImage, '/vision/image/compressed', latest)
        self.people_pub = self.create_publisher(Detection2DArray, '/vision/people', 10)
        self.faces_pub = self.create_publisher(Detection2DArray, '/vision/faces', 10)
        self.annotations_pub = self.create_publisher(ImageAnnotations, '/vision/annotations', 10)

        self.frames = 0
        self.busy = 0.0
        self.last_report = time.monotonic()
        self.create_timer(5.0, self.report)
        self.get_logger().info(
            f'people: {person_model}, input {self.people.input_size}; faces: {face_model}')

    def on_image(self, msg):
        t0 = time.perf_counter()
        self.image_pub.publish(msg)
        bgr = cv2.imdecode(np.frombuffer(msg.data, np.uint8), cv2.IMREAD_COLOR)
        if bgr is None:
            self.get_logger().warn('could not decode a frame', throttle_duration_sec=5.0)
            return
        people = self.people.detect(bgr)
        faces = self.faces.detect(bgr)
        self.people_pub.publish(self.to_detections(msg.header, people, 'person'))
        self.faces_pub.publish(self.to_detections(msg.header, faces, 'face'))
        annotations = ImageAnnotations()
        self.annotate(annotations, msg.header.stamp, people, 'person', PERSON_COLOR)
        self.annotate(annotations, msg.header.stamp, faces, 'face', FACE_COLOR)
        self.annotations_pub.publish(annotations)
        self.frames += 1
        self.busy += time.perf_counter() - t0

    @staticmethod
    def to_detections(header, boxes, class_id):
        out = Detection2DArray(header=header)
        for x, y, w, h, score in boxes:
            d = Detection2D(header=header)
            d.bbox.center.position.x = float(x + w / 2)
            d.bbox.center.position.y = float(y + h / 2)
            d.bbox.size_x = float(w)
            d.bbox.size_y = float(h)
            hyp = ObjectHypothesisWithPose()
            hyp.hypothesis.class_id = class_id
            hyp.hypothesis.score = score
            d.results.append(hyp)
            out.detections.append(d)
        return out

    @staticmethod
    def annotate(out, stamp, boxes, label, color):
        for x, y, w, h, score in boxes:
            corners = [(x, y), (x + w, y), (x + w, y + h), (x, y + h)]
            out.points.append(PointsAnnotation(
                timestamp=stamp, type=PointsAnnotation.LINE_LOOP,
                points=[Point2(x=float(px), y=float(py)) for px, py in corners],
                outline_color=color, thickness=3.0))
            out.texts.append(TextAnnotation(
                timestamp=stamp, position=Point2(x=float(x), y=float(y)),
                text=f'{label} {score:.2f}', font_size=16.0,
                text_color=WHITE, background_color=TEXT_BG))

    def report(self):
        now = time.monotonic()
        fps = self.frames / (now - self.last_report)
        ms = self.busy / self.frames * 1000 if self.frames else 0.0
        self.get_logger().info(f'{fps:.1f} frames/s, {ms:.1f} ms each (decode + detect)')
        self.frames, self.busy, self.last_report = 0, 0.0, now


def main():
    # Python's own signal handling, not rclpy's: Ctrl-C raises
    # KeyboardInterrupt here, docker stop (SIGTERM) just ends the process.
    # rclpy's handlers shut the context down under a running callback.
    rclpy.init(signal_handler_options=SignalHandlerOptions.NO)
    try:
        rclpy.spin(VisionNode())
    except KeyboardInterrupt:
        pass


if __name__ == '__main__':
    main()
