"""YuNet face detector (OpenCV Zoo), through OpenCV's FaceDetectorYN.

OpenCV does YuNet's pre- and post-processing itself; the model also gives
five landmarks per face (eyes, nose, mouth corners), not used yet.
"""
import cv2


class FaceDetector:
    def __init__(self, model_path, score_threshold=0.6, nms_threshold=0.3, threads=2):
        # OpenCV's thread pool defaults to every core, like ONNX Runtime's
        # (yolox.py); a small model on one 640x480 frame doesn't need them.
        cv2.setNumThreads(threads)
        self.detector = cv2.FaceDetectorYN.create(
            model_path, '', (0, 0), score_threshold, nms_threshold)
        self.size = None

    def detect(self, bgr):
        """Return [(x, y, w, h, score)] in image pixels, x/y the top left."""
        size = (bgr.shape[1], bgr.shape[0])
        if size != self.size:
            self.detector.setInputSize(size)
            self.size = size
        _, faces = self.detector.detect(bgr)
        if faces is None:
            return []
        return [(*f[:4], float(f[14])) for f in faces]
