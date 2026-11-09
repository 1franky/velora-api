package mx.velora.api.importing.parsing;

import java.io.InputStream;
import javax.xml.XMLConstants;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import mx.velora.api.importing.ImportRejectedException;
import mx.velora.api.importing.RejectionReason;

/**
 * Fábrica de lectores StAX endurecidos para archivos no confiables.
 *
 * <p>Usa siempre la implementación del JDK, deshabilita DTD y entidades externas y bloquea el acceso a recursos
 * externos. Los parsers además rechazan cualquier evento {@code DTD} con {@link #rejectDtd()}: GPX y TCX no
 * necesitan DTD, así que su presencia se trata como un intento de XXE o de expansión de entidades.
 */
final class SafeXml {

    private SafeXml() {}

    static XMLStreamReader createReader(InputStream content) {
        var factory = XMLInputFactory.newDefaultFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        factory.setProperty(XMLInputFactory.IS_REPLACING_ENTITY_REFERENCES, false);
        factory.setProperty(XMLInputFactory.IS_COALESCING, true);
        factory.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        try {
            return factory.createXMLStreamReader(content);
        } catch (XMLStreamException e) {
            throw malformed(e);
        }
    }

    static ImportRejectedException rejectDtd() {
        return new ImportRejectedException(
                RejectionReason.FORBIDDEN_XML_CONSTRUCT, "el documento declara un DTD, que no está permitido");
    }

    /** Traduce un error de StAX sin propagar su mensaje, que puede contener contenido del archivo. */
    static ImportRejectedException malformed(XMLStreamException e) {
        var location = e.getLocation();
        var line = location != null ? location.getLineNumber() : -1;
        return new ImportRejectedException(
                RejectionReason.MALFORMED_FILE, line > 0 ? "XML inválido cerca de la línea " + line : "XML inválido");
    }

    static void closeQuietly(XMLStreamReader reader) {
        try {
            reader.close();
        } catch (XMLStreamException ignored) {
            // Cerrar el lector no cierra el stream subyacente; no hay recurso que liberar.
        }
    }
}
