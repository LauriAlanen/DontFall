"""DontFall person following on the PC, dry run (docs/camera-plan.md step 6).

Listens to /vision/people and suggests how the robot should move to follow
one person:
  /follow/cmd_vel      geometry_msgs/Twist, the suggestion. Never /cmd_vel:
                       nothing drives the robot from it yet
  /follow/annotations  foxglove_msgs/ImageAnnotations: the person it follows
                       and which way it would go, for Foxglove's Image panel
With people seen but nobody to follow it suggests standing still. When the
detections stop (vision node or PC gone) it says nothing: whatever uses the
suggestion later must time out, like the M4 does with /cmd_vel.
"""
import rclpy
from foxglove_msgs.msg import Color, ImageAnnotations, Point2, PointsAnnotation, TextAnnotation
from geometry_msgs.msg import Twist
from rclpy.node import Node
from rclpy.signals import SignalHandlerOptions
from vision_msgs.msg import Detection2DArray

from follow import FollowParams, follow_speed, pick_target

TARGET_COLOR = Color(r=0.2, g=0.7, b=1.0, a=1.0)
TEXT_BG = Color(r=0.0, g=0.0, b=0.0, a=0.6)
WHITE = Color(r=1.0, g=1.0, b=1.0, a=1.0)


class FollowNode(Node):
    def __init__(self):
        super().__init__('dontfall_follow')
        self.width = self.declare_parameter('camera_width', 640).value
        self.height = self.declare_parameter('camera_height', 480).value
        defaults = FollowParams()
        self.params = FollowParams(**{
            name: float(self.declare_parameter(name, value).value)
            for name, value in vars(defaults).items()})
        # Smoothing of the suggestion: each frame moves it this part of the
        # way to the new value, so a jumpy box doesn't make a jumpy robot.
        self.smoothing = self.declare_parameter('smoothing', 0.4).value
        self.target = None
        self.speed = 0.0
        self.turn = 0.0
        self.create_subscription(Detection2DArray, '/vision/people', self.on_people, 1)
        self.cmd_pub = self.create_publisher(Twist, '/follow/cmd_vel', 10)
        self.annotations_pub = self.create_publisher(ImageAnnotations, '/follow/annotations', 10)

    def on_people(self, msg):
        # Pixels to -1..1 from the middle, height as a part of the image.
        boxes = [(d.bbox.center.position.x / self.width * 2 - 1,
                  d.bbox.center.position.y / self.height * 2 - 1,
                  d.bbox.size_x / self.width, d.bbox.size_y / self.height)
                 for d in msg.detections]
        self.target = pick_target(boxes, self.target, self.params)
        speed, turn = follow_speed(self.target, self.params) if self.target else (0.0, 0.0)
        a = self.smoothing
        self.speed += a * (speed - self.speed)
        self.turn += a * (turn - self.turn)

        cmd = Twist()
        cmd.linear.x = self.speed
        cmd.angular.z = self.turn
        self.cmd_pub.publish(cmd)
        self.annotations_pub.publish(self.annotate(msg.header.stamp))

    def annotate(self, stamp):
        out = ImageAnnotations()
        if self.target is None:
            return out
        cx, cy, w, h = self.target
        # Back to pixels: the box, and a line from the image's middle to the
        # person (what the turn tries to shrink).
        x0 = (cx + 1) / 2 * self.width - w * self.width / 2
        y0 = (cy + 1) / 2 * self.height - h * self.height / 2
        x1, y1 = x0 + w * self.width, y0 + h * self.height
        corners = [(x0, y0), (x1, y0), (x1, y1), (x0, y1)]
        out.points.append(PointsAnnotation(
            timestamp=stamp, type=PointsAnnotation.LINE_LOOP,
            points=[Point2(x=float(x), y=float(y)) for x, y in corners],
            outline_color=TARGET_COLOR, thickness=5.0))
        middle = Point2(x=self.width / 2, y=self.height / 2)
        person = Point2(x=float((cx + 1) / 2 * self.width), y=self.height / 2)
        out.points.append(PointsAnnotation(
            timestamp=stamp, type=PointsAnnotation.LINE_STRIP, points=[middle, person],
            outline_color=TARGET_COLOR, thickness=3.0))
        out.texts.append(TextAnnotation(
            timestamp=stamp, position=Point2(x=float(x0), y=float(y1)),
            text=f'follow: {self.speed:+.2f} m/s, turn {self.turn:+.2f} rad/s',
            font_size=16.0, text_color=WHITE, background_color=TEXT_BG))
        return out


def main():
    # Python's own signal handling, see vision_node.py.
    rclpy.init(signal_handler_options=SignalHandlerOptions.NO)
    try:
        rclpy.spin(FollowNode())
    except KeyboardInterrupt:
        pass


if __name__ == '__main__':
    main()
