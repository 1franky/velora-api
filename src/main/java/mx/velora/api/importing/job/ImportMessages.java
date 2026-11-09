package mx.velora.api.importing.job;

import mx.velora.api.importing.ImportRejectedException;

/**
 * Textos es-MX guardados en {@code error_detail_safe}. Son datos del trabajo que la API devuelve tal cual; nunca
 * incluyen contenido del archivo.
 */
final class ImportMessages {

    static final String INTERNAL_ERROR =
            "No fue posible procesar el archivo por un error interno. Puedes intentar importarlo de nuevo.";

    private ImportMessages() {}

    /** "MOTIVO: detalle en minúsculas" → "Detalle en minúsculas." */
    static String detail(ImportRejectedException e) {
        var message = e.getMessage();
        var separator = message.indexOf(": ");
        var detail = separator >= 0 ? message.substring(separator + 2) : message;
        if (detail.isEmpty()) {
            return "El archivo no es válido.";
        }
        var sentence = Character.toUpperCase(detail.charAt(0)) + detail.substring(1);
        return sentence.endsWith(".") ? sentence : sentence + ".";
    }
}
