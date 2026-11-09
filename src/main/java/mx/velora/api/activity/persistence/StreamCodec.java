package mx.velora.api.activity.persistence;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;
import org.jspecify.annotations.Nullable;

/**
 * Códec {@value #ENCODING} (ADR-012).
 *
 * <pre>
 * byte 0      versión (1)
 * resto       deflate de:
 *               varint   número de muestras
 *               byte     0 = todas presentes; 1 = sigue un mapa de presencia
 *               bytes    mapa de presencia, 1 bit por muestra (LSB primero), si aplica
 *               varints  diferencias zigzag entre valores presentes cuantizados (round(valor × escala))
 * </pre>
 *
 * El error de cuantización es como máximo media unidad de la escala.
 */
public final class StreamCodec {

    public static final String ENCODING = "velora-streams/1";

    private static final int VERSION = 1;

    /** Límite defensivo al decodificar: muy por encima del máximo de muestras admitido al importar. */
    private static final int MAX_SAMPLES = 10_000_000;

    private StreamCodec() {}

    public static byte[] encode(@Nullable Double[] values, double scale) {
        var bytes = new ByteArrayOutputStream();
        bytes.write(VERSION);
        try (OutputStream out =
                new BufferedOutputStream(new DeflaterOutputStream(bytes, new Deflater(Deflater.BEST_SPEED)), 8192)) {
            writeVarLong(out, values.length);
            var allPresent = true;
            for (var value : values) {
                if (value == null) {
                    allPresent = false;
                    break;
                }
            }
            out.write(allPresent ? 0 : 1);
            if (!allPresent) {
                var bitmap = new byte[(values.length + 7) / 8];
                for (int i = 0; i < values.length; i++) {
                    if (values[i] != null) {
                        bitmap[i >> 3] |= (byte) (1 << (i & 7));
                    }
                }
                out.write(bitmap);
            }
            long previous = 0;
            for (var value : values) {
                if (value != null) {
                    if (!Double.isFinite(value)) {
                        throw new IllegalArgumentException("Valor no finito en stream");
                    }
                    var quantized = Math.round(value * scale);
                    writeVarLong(out, zigzag(quantized - previous));
                    previous = quantized;
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    public static @Nullable Double[] decode(byte[] encoded, double scale) {
        if (encoded.length == 0 || encoded[0] != VERSION) {
            throw new IllegalArgumentException("Versión de códec de streams no soportada");
        }
        try (InputStream in = new BufferedInputStream(
                new InflaterInputStream(new ByteArrayInputStream(encoded, 1, encoded.length - 1)), 8192)) {
            var count = readVarLong(in);
            if (count < 0 || count > MAX_SAMPLES) {
                throw new IllegalArgumentException("Número de muestras inválido");
            }
            var values = new Double[(int) count];
            var flag = in.read();
            byte[] bitmap = null;
            if (flag == 1) {
                bitmap = in.readNBytes((values.length + 7) / 8);
            } else if (flag != 0) {
                throw new IllegalArgumentException("Stream corrupto");
            }
            long previous = 0;
            for (int i = 0; i < values.length; i++) {
                if (bitmap == null || (bitmap[i >> 3] & (1 << (i & 7))) != 0) {
                    previous += unzigzag(readVarLong(in));
                    values[i] = previous / scale;
                }
            }
            return values;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static long zigzag(long v) {
        return (v << 1) ^ (v >> 63);
    }

    private static long unzigzag(long v) {
        return (v >>> 1) ^ -(v & 1);
    }

    private static void writeVarLong(OutputStream out, long v) throws IOException {
        while ((v & ~0x7FL) != 0) {
            out.write((int) ((v & 0x7F) | 0x80));
            v >>>= 7;
        }
        out.write((int) v);
    }

    private static long readVarLong(InputStream in) throws IOException {
        long result = 0;
        for (int shift = 0; shift < 64; shift += 7) {
            var b = in.read();
            if (b == -1) {
                throw new IllegalArgumentException("Stream truncado");
            }
            result |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return result;
            }
        }
        throw new IllegalArgumentException("Varint demasiado largo");
    }
}
