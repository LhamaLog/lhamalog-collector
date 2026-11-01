// Author: LhamaLog Desenvolvimento (IA) - LLT-181
package br.com.lhamalog.collector.ingestion.otlp.http;

import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import com.google.protobuf.util.JsonFormat;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * OTLP/JSON encoding (T019). It differs from the canonical protobuf JSON in one point: {@code traceId},
 * {@code spanId} and {@code parentSpanId} are hex strings instead of base64. Enums arrive as integers, which
 * {@link JsonFormat} already accepts; unknown fields are ignored, as the OTLP spec asks.
 */
public final class OtlpJson {

	private static final Set<String> ID_FIELDS = Set.of("traceId", "spanId", "parentSpanId");

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final JsonFormat.Parser PARSER = JsonFormat.parser().ignoringUnknownFields();

	private static final JsonFormat.Printer PRINTER = JsonFormat.printer().omittingInsignificantWhitespace();

	private OtlpJson() {
	}

	public static <B extends Message.Builder> B parse(byte[] json, B builder) throws InvalidProtocolBufferException {
		JsonNode tree;
		try {
			tree = JSON.readTree(json);
		}
		catch (RuntimeException ex) {
			throw new InvalidProtocolBufferException("body is not valid JSON");
		}
		hexIdsToBase64(tree);
		PARSER.merge(tree.toString(), builder);
		return builder;
	}

	public static String print(Message message) {
		try {
			return PRINTER.print(message);
		}
		catch (InvalidProtocolBufferException ex) {
			throw new IllegalStateException(ex);
		}
	}

	private static void hexIdsToBase64(JsonNode node) {
		if (node instanceof ObjectNode object) {
			for (String name : Set.copyOf(object.propertyNames())) {
				JsonNode child = object.get(name);
				if (ID_FIELDS.contains(name) && child.isString()) {
					String value = child.asString();
					if (!value.isEmpty() && HexFormat.isHexDigit(value.charAt(0)) && value.chars().allMatch(HexFormat::isHexDigit)
							&& value.length() % 2 == 0) {
						object.put(name, Base64.getEncoder().encodeToString(HexFormat.of().parseHex(value)));
					}
				}
				else {
					hexIdsToBase64(child);
				}
			}
		}
		else if (node != null && node.isArray()) {
			node.forEach(OtlpJson::hexIdsToBase64);
		}
	}

}
