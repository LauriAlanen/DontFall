"""Following a person: from their box in the camera image to a speed.

Plain maths, no ROS (follow_node.py does the ROS side). A box is
(cx, cy, w, h) in -1..1 image units from the middle, + = right and down,
h as a part of the image height (0..1).
"""
from dataclasses import dataclass
import math


@dataclass
class FollowParams:
    # Keep the person's box this tall (part of the image height): the
    # distance to stand at. On the bench the camera sees people from the
    # waist up, so this is a guess until the robot exists.
    target_height: float = 0.6
    # Speed per unit of error, and the most it may ask for (like teleop).
    turn_gain: float = 0.8       # rad/s per image half-width off centre
    speed_gain: float = 0.5      # m/s per part of image height off target
    max_turn: float = 0.5        # rad/s
    max_speed: float = 0.2       # m/s
    # Errors smaller than this ask for nothing: no hunting around the goal.
    turn_dead_band: float = 0.1
    height_dead_band: float = 0.05
    # A box whose centre moved less than this since the last frame is the
    # same person (image units); otherwise the biggest box is the target.
    same_person: float = 0.4


def pick_target(boxes, last, params):
    """The box to follow: the one nearest the last target if close enough,
    else the biggest. None if there are no boxes."""
    if not boxes:
        return None
    if last is not None:
        nearest = min(boxes, key=lambda b: math.dist(b[:2], last[:2]))
        if math.dist(nearest[:2], last[:2]) < params.same_person:
            return nearest
    return max(boxes, key=lambda b: b[2] * b[3])


def _shape(error, dead_band, gain, limit):
    if abs(error) < dead_band:
        return 0.0
    # Start from zero at the dead band's edge, not with a jump.
    error -= math.copysign(dead_band, error)
    return max(-limit, min(limit, gain * error))


def follow_speed(box, params):
    """(forward m/s, turn rad/s) that brings the person to the middle at
    the set distance. ROS conventions: + forward, + turn = left. The
    camera looks forward, so a person right of centre needs a right turn."""
    cx, _, _, h = box
    turn = -_shape(cx, params.turn_dead_band, params.turn_gain, params.max_turn)
    speed = _shape(params.target_height - h, params.height_dead_band,
                   params.speed_gain, params.max_speed)
    return speed, turn
