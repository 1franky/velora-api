package mx.velora.api.importing.parsing;

import java.io.InputStream;
import mx.velora.api.importing.FileFormat;
import mx.velora.api.importing.ImportRejectedException;
import mx.velora.api.importing.ImportedActivity;

/** Parser de un formato concreto. Las implementaciones no deben registrar contenido del archivo. */
public interface ActivityFileParser {

    FileFormat format();

    /**
     * Lee el archivo completo. No cierra {@code content}; lo hace quien lo abrió.
     *
     * @throws ImportRejectedException si el contenido es inválido o supera los límites
     */
    ImportedActivity parse(InputStream content);
}
