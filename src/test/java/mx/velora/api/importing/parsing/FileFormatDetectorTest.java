package mx.velora.api.importing.parsing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Random;
import mx.velora.api.importing.FileFormat;
import mx.velora.api.importing.ImportRejectedException;
import mx.velora.api.importing.RejectionReason;
import org.junit.jupiter.api.Test;

class FileFormatDetectorTest {

    @Test
    void detectsFitByHeaderSignature() throws IOException {
        assertThat(detect(fitHeader(14)).format()).isEqualTo(FileFormat.FIT);
        assertThat(detect(fitHeader(12)).format()).isEqualTo(FileFormat.FIT);
    }

    @Test
    void doesNotTreatInvalidHeaderSizeAsFit() {
        assertRejected(fitHeader(13), RejectionReason.UNSUPPORTED_FORMAT);
    }

    @Test
    void detectsGpxByRootElement() throws IOException {
        assertThat(detect(Fixtures.bytes("gpx/with-time-and-sensors.gpx")).format())
                .isEqualTo(FileFormat.GPX);
    }

    @Test
    void detectsTcxByRootElement() throws IOException {
        var tcx = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!-- comentario antes de la raíz -->
                <TrainingCenterDatabase xmlns="http://www.garmin.com/xmlschemas/TrainingCenterDatabase/v2"/>
                """;

        assertThat(detect(tcx.getBytes(StandardCharsets.UTF_8)).format()).isEqualTo(FileFormat.TCX);
    }

    @Test
    void detectsXmlWithByteOrderMark() throws IOException {
        var gpx = "﻿<gpx xmlns=\"http://www.topografix.com/GPX/1/1\"/>";

        assertThat(detect(gpx.getBytes(StandardCharsets.UTF_8)).format()).isEqualTo(FileFormat.GPX);
    }

    @Test
    void rejectsOtherXmlDocuments() {
        assertRejected(
                "<kml xmlns=\"http://www.opengis.net/kml/2.2\"/>".getBytes(StandardCharsets.UTF_8),
                RejectionReason.UNSUPPORTED_FORMAT);
    }

    @Test
    void rejectsBinaryGarbage() {
        var garbage = new byte[2048];
        new Random(42).nextBytes(garbage);
        garbage[0] = 0x50; // Evita una cabecera FIT accidental.

        assertRejected(garbage, RejectionReason.UNSUPPORTED_FORMAT);
    }

    @Test
    void rejectsEmptyFile() {
        assertRejected(new byte[0], RejectionReason.EMPTY_FILE);
    }

    @Test
    void rejectsDtdDuringDetection() {
        assertRejected(Fixtures.bytes("gpx/xxe.gpx"), RejectionReason.FORBIDDEN_XML_CONSTRUCT);
    }

    @Test
    void preservesFullContentLargerThanProbe() throws IOException {
        var content = new byte[FileFormatDetector.PROBE_BYTES * 3 + 17];
        new Random(7).nextBytes(content);
        System.arraycopy(fitHeader(14), 0, content, 0, 14);

        var detection = detect(content);

        assertThat(detection.content().readAllBytes()).isEqualTo(content);
    }

    private static FileFormatDetector.Detection detect(byte[] content) throws IOException {
        return FileFormatDetector.detect(new ByteArrayInputStream(content));
    }

    private static void assertRejected(byte[] content, RejectionReason reason) {
        assertThatThrownBy(() -> detect(content))
                .isInstanceOfSatisfying(
                        ImportRejectedException.class,
                        e -> assertThat(e.reason()).isEqualTo(reason));
    }

    /** Cabecera FIT sintética: tamaño, versiones, longitud de datos y firma ".FIT". */
    private static byte[] fitHeader(int headerSize) {
        var header = Arrays.copyOf(new byte[] {(byte) headerSize, 0x20, 0x00, 0x08}, Math.max(headerSize, 12));
        header[8] = '.';
        header[9] = 'F';
        header[10] = 'I';
        header[11] = 'T';
        return header;
    }
}
