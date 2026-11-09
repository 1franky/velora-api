package mx.velora.api.importing.parsing;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import mx.velora.api.importing.FileFormat;
import mx.velora.api.importing.ImportRejectedException;
import mx.velora.api.importing.RejectionReason;

/**
 * Detecta el formato por contenido: cabecera binaria {@code .FIT} o elemento raíz XML. La extensión del archivo no
 * se usa.
 */
public final class FileFormatDetector {

    /** Bytes inspeccionados; el elemento raíz de GPX/TCX debe aparecer dentro de este prefijo. */
    static final int PROBE_BYTES = 64 * 1024;

    private static final byte[] FIT_SIGNATURE = {'.', 'F', 'I', 'T'};

    private FileFormatDetector() {}

    /**
     * @param format formato detectado
     * @param content contenido completo, incluido el prefijo ya leído para la detección
     */
    public record Detection(FileFormat format, InputStream content) {}

    public static Detection detect(InputStream input) throws IOException {
        var head = input.readNBytes(PROBE_BYTES);
        if (head.length == 0) {
            throw new ImportRejectedException(RejectionReason.EMPTY_FILE, "el archivo está vacío");
        }
        var format = isFit(head) ? FileFormat.FIT : detectXml(head);
        return new Detection(format, new SequenceInputStream(new ByteArrayInputStream(head), input));
    }

    /** Cabecera FIT: tamaño 12 o 14 bytes y la firma ".FIT" en los bytes 8–11. */
    private static boolean isFit(byte[] head) {
        if (head.length < 12 || (head[0] != 12 && head[0] != 14)) {
            return false;
        }
        for (int i = 0; i < FIT_SIGNATURE.length; i++) {
            if (head[8 + i] != FIT_SIGNATURE[i]) {
                return false;
            }
        }
        return true;
    }

    private static FileFormat detectXml(byte[] head) {
        XMLStreamReader reader;
        try {
            reader = SafeXml.createReader(new ByteArrayInputStream(head));
        } catch (ImportRejectedException e) {
            // Ni siquiera tiene una declaración/codificación XML legible: no es un formato admitido.
            throw unsupported();
        }
        try {
            while (reader.hasNext()) {
                switch (reader.next()) {
                    case XMLStreamConstants.DTD -> throw SafeXml.rejectDtd();
                    case XMLStreamConstants.START_ELEMENT -> {
                        return switch (reader.getLocalName()) {
                            case "gpx" -> FileFormat.GPX;
                            case "TrainingCenterDatabase" -> FileFormat.TCX;
                            default -> throw unsupported();
                        };
                    }
                    default -> {
                        // Prólogo, comentarios o espacios: seguir buscando el elemento raíz.
                    }
                }
            }
            throw unsupported();
        } catch (XMLStreamException e) {
            // No es XML bien formado dentro del prefijo: no es un formato admitido.
            throw unsupported();
        } finally {
            SafeXml.closeQuietly(reader);
        }
    }

    private static ImportRejectedException unsupported() {
        return new ImportRejectedException(
                RejectionReason.UNSUPPORTED_FORMAT, "el contenido no corresponde a FIT, GPX ni TCX");
    }
}
