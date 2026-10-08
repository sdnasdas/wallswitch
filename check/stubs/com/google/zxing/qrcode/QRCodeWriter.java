package com.google.zxing.qrcode;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;

import java.util.Map;

/** 本地类型检查桩。 */
public final class QRCodeWriter {
    public QRCodeWriter() {
    }

    public BitMatrix encode(String contents, BarcodeFormat format, int width, int height)
            throws WriterException {
        throw new UnsupportedOperationException();
    }

    public BitMatrix encode(String contents, BarcodeFormat format, int width, int height,
                            Map<EncodeHintType, ?> hints) throws WriterException {
        throw new UnsupportedOperationException();
    }
}
