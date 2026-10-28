package br.com.lhamalog.collector.ingestion.otlp.http;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import com.google.protobuf.MessageOrBuilder;
import com.google.protobuf.util.JsonFormat;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;

/**
 * Conversao OTLP/JSON (https://opentelemetry.io/docs/specs/otlp/#json-protobuf-encoding).
 *
 * <p>Diferencas em relacao ao mapeamento JSON padrao do protobuf, tratadas aqui:
 * traceId/spanId/parentSpanId chegam em hexadecimal (e nao base64); enums chegam como inteiros
 * (aceito pelo {@link JsonFormat}); campos em lowerCamelCase (aceito, assim como o nome original);
 * campos desconhecidos sao ignorados.
 */
public final class OtlpJson {

    private static final Set<String> HEX_ID_FIELDS =
            Set.of("traceId", "spanId", "parentSpanId", "trace_id", "span_id", "parent_span_id");

    private static final JsonFormat.Parser PARSER = JsonFormat.parser().ignoringUnknownFields();
    private static final JsonFormat.Printer PRINTER = JsonFormat.printer().omittingInsignificantWhitespace();

    private OtlpJson() {
    }

    public static void merge(String json, Message.Builder builder) throws InvalidProtocolBufferException {
        JsonElement root;
        try {
            root = JsonParser.parseString(json);
        } catch (JsonParseException e) {
            throw new InvalidProtocolBufferException("JSON invalido: " + e.getMessage());
        }
        if (!root.isJsonObject()) {
            throw new InvalidProtocolBufferException("JSON invalido: esperado um objeto");
        }
        convertHexIds(root);
        PARSER.merge(root.toString(), builder);
    }

    public static String print(MessageOrBuilder message) {
        try {
            return PRINTER.print(message);
        } catch (InvalidProtocolBufferException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void convertHexIds(JsonElement element) throws InvalidProtocolBufferException {
        if (element.isJsonObject()) {
            JsonObject object = element.getAsJsonObject();
            for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
                JsonElement value = entry.getValue();
                if (HEX_ID_FIELDS.contains(entry.getKey()) && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
                    entry.setValue(new JsonPrimitive(hexToBase64(entry.getKey(), value.getAsString())));
                } else {
                    convertHexIds(value);
                }
            }
        } else if (element.isJsonArray()) {
            JsonArray array = element.getAsJsonArray();
            for (JsonElement item : array) {
                convertHexIds(item);
            }
        }
    }

    private static String hexToBase64(String field, String hex) throws InvalidProtocolBufferException {
        if (hex.isEmpty()) {
            return hex;
        }
        try {
            return Base64.getEncoder().encodeToString(HexFormat.of().parseHex(hex));
        } catch (IllegalArgumentException e) {
            throw new InvalidProtocolBufferException("campo " + field + " deve ser hexadecimal: " + hex);
        }
    }
}
