"""YOLOX person detector on ONNX Runtime.

The models are Megvii's released ONNX exports (COCO, 80 classes). They
output raw grid predictions, so the decoding and NMS from YOLOX's own
ONNX Runtime demo are done here. Only class 0 (person) is kept.
"""
import cv2
import numpy as np
import onnxruntime as ort

PERSON = 0
STRIDES = (8, 16, 32)


class PersonDetector:
    def __init__(self, model_path, score_threshold=0.4, nms_threshold=0.45):
        self.session = ort.InferenceSession(model_path, providers=['CPUExecutionProvider'])
        self.input_name = self.session.get_inputs()[0].name
        _, _, h, w = self.session.get_inputs()[0].shape
        self.input_size = (h, w)
        self.score_threshold = score_threshold
        self.nms_threshold = nms_threshold
        self.grids, self.strides = self._make_grids(h, w)

    @staticmethod
    def _make_grids(h, w):
        # Each output row belongs to one cell of the 8, 16 and 32 pixel
        # grids; its x/y are offsets in that cell, w/h log-scaled.
        grids, strides = [], []
        for s in STRIDES:
            xv, yv = np.meshgrid(np.arange(w // s), np.arange(h // s))
            grid = np.stack((xv, yv), 2).reshape(-1, 2)
            grids.append(grid)
            strides.append(np.full((grid.shape[0], 1), s))
        return np.concatenate(grids), np.concatenate(strides)

    def _preprocess(self, bgr):
        # Letterbox: scale to fit, pad right/bottom with grey (114). The
        # 0.1.1rc0 models take BGR 0-255, no normalisation.
        h, w = self.input_size
        r = min(h / bgr.shape[0], w / bgr.shape[1])
        resized = cv2.resize(bgr, (int(bgr.shape[1] * r), int(bgr.shape[0] * r)))
        padded = np.full((h, w, 3), 114, dtype=np.uint8)
        padded[:resized.shape[0], :resized.shape[1]] = resized
        blob = padded.transpose(2, 0, 1)[np.newaxis].astype(np.float32)
        return blob, r

    def detect(self, bgr):
        """Return [(x, y, w, h, score)] in image pixels, x/y the top left."""
        blob, r = self._preprocess(bgr)
        out = self.session.run(None, {self.input_name: blob})[0][0]
        xy = (out[:, :2] + self.grids) * self.strides
        wh = np.exp(out[:, 2:4]) * self.strides
        scores = out[:, 4] * out[:, 5 + PERSON]  # objectness * class

        keep = scores > self.score_threshold
        if not keep.any():
            return []
        xy, wh, scores = xy[keep], wh[keep], scores[keep]
        boxes = np.concatenate((xy - wh / 2, wh), axis=1) / r
        idx = cv2.dnn.NMSBoxes(boxes.tolist(), scores.tolist(),
                               self.score_threshold, self.nms_threshold)
        return [(*boxes[i], float(scores[i])) for i in np.array(idx).flatten()]
