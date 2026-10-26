"""DontFall vision on the PC (docs/camera-plan.md step 4).

Subscribes to the board's camera once, finds people with YOLOX and
publishes:
  /vision/image/compressed  the frame it looked at, unchanged, so Foxglove
                            (and anything else on the PC) shows it without
                            a second copy over WiFi
  /vision/people            vision_msgs/Detection2DArray, stamped with the
                            frame's capture time
  /vision/annotations       foxglove_msgs/ImageAnnotations: the boxes, for
                            Foxglove's Image panel
It only publishes what it sees; it never commands the robot.
"""
import time

import cv2
import numpy as np
import rclpy
from foxglove_msgs.msg import Color, ImageAnnotations, Point2, PointsAnnotation, TextAnnotation
from rclpy.executors import ExternalShutdownException
from rclpy.node import Node
from rclpy.qos import HistoryPolicy, QoSProfile, ReliabilityPolicy
from sensor_msgs.msg import CompressedImage
from vision_msgs.msg import Detection2D, Detection2DArray, ObjectHypothesisWithPose

from yolox import PersonDetector

BOX_COLOR = Color(r=0.2, g=0.9, b=0.4, a=1.0)
TEXT_BG = Color(r=0.0, g=0.0, b=0.0, a=0.6)
WHITE = Color(r=1.0, g=1.0, b=1.0, a=1.0)


class VisionNode(Node):
    def __init__(self):
        super().__init__('dontfall_vision')
        model = self.declare_parameter('model', '/opt/models/yolox_tiny.onnx').value
        threshold = self.declare_parameter('score_threshold', 0.4).value
        self.detector = PersonDetector(model, threshold)

        # Only the newest frame: when a detection is slower than the camera,
        # older frames are skipped, never queued (a late box is a wrong box).
        latest = QoSProfile(depth=1, reliability=ReliabilityPolicy.BEST_EFFORT,
                            history=HistoryPolicy.KEEP_LAST)
        self.create_subscription(CompressedImage, '/camera/image_raw/compressed',
                                 self.on_image, latest)
        self.image_pub = self.create_publisher(CompressedImage, '/vision/image/compressed', latest)
        self.people_pub = self.create_publisher(Detection2DArray, '/vision/people', 10)
        self.annotations_pub = self.create_publisher(ImageAnnotations, '/vision/annotations', 10)

        self.frames = 0
        self.busy = 0.0
        self.last_report = time.monotonic()
        self.create_timer(5.0, self.report)
        self.get_logger().info(f'model {model}, input {self.detector.input_size}')

    def on_image(self, msg):
        t0 = time.perf_counter()
        self.image_pub.publish(msg)
        bgr = cv2.imdecode(np.frombuffer(msg.data, np.uint8), cv2.IMREAD_COLOR)
        if bgr is None:
            self.get_logger().warn('could not decode a frame', throttle_duration_sec=5.0)
            return
        people = self.detector.detect(bgr)
        self.people_pub.publish(self.to_detections(msg.header, people))
        self.annotations_pub.publish(self.to_annotations(msg.header.stamp, people))
        self.frames += 1
        self.busy += time.perf_counter() - t0

    @staticmethod
    def to_detections(header, people):
        out = Detection2DArray(header=header)
        for x, y, w, h, score in people:
            d = Detection2D(header=header)
            d.bbox.center.position.x = float(x + w / 2)
            d.bbox.center.position.y = float(y + h / 2)
            d.bbox.size_x = float(w)
            d.bbox.size_y = float(h)
            hyp = ObjectHypothesisWithPose()
            hyp.hypothesis.class_id = 'person'
            hyp.hypothesis.score = score
            d.results.append(hyp)
            out.detections.append(d)
        return out

    @staticmethod
    def to_annotations(stamp, people):
        out = ImageAnnotations()
        for x, y, w, h, score in people:
            corners = [(x, y), (x + w, y), (x + w, y + h), (x, y + h)]
            out.points.append(PointsAnnotation(
                timestamp=stamp, type=PointsAnnotation.LINE_LOOP,
                points=[Point2(x=float(px), y=float(py)) for px, py in corners],
                outline_color=BOX_COLOR, thickness=3.0))
            out.texts.append(TextAnnotation(
                timestamp=stamp, position=Point2(x=float(x), y=float(y)),
                text=f'person {score:.2f}', font_size=16.0,
                text_color=WHITE, background_color=TEXT_BG))
        return out

    def report(self):
        now = time.monotonic()
        fps = self.frames / (now - self.last_report)
        ms = self.busy / self.frames * 1000 if self.frames else 0.0
        self.get_logger().info(f'{fps:.1f} frames/s, {ms:.1f} ms each (decode + detect)')
        self.frames, self.busy, self.last_report = 0, 0.0, now


def main():
    rclpy.init()
    try:
        rclpy.spin(VisionNode())
    except (KeyboardInterrupt, ExternalShutdownException):
        pass  # Ctrl-C or docker stop


if __name__ == '__main__':
    main()
