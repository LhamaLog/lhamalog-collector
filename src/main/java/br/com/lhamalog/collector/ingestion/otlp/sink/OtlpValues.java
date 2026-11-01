// Author: LhamaLog Desenvolvimento (IA) - LLT-181
package br.com.lhamalog.collector.ingestion.otlp.sink;

import br.com.lhamalog.collector.ingestion.otlp.domain.Scoped;
import com.google.protobuf.ByteString;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.KeyValue;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;

/** Conversions from OTLP values to the flat JSON documents written to OpenObserve. */
final class OtlpValues {

	static final String SERVICE_NAME = "service.name";

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private OtlpValues() {
	}

	static String hex(ByteString bytes) {
		return HexFormat.of().formatHex(bytes.toByteArray());
	}

	/** Attributes as JSON text, so a key of the batch can never collide with a top level field. */
	static String attributesJson(List<KeyValue> attributes) {
		return JSON.writeValueAsString(toMap(attributes));
	}

	/** Any plain value (lists and maps of plain values) as JSON text. */
	static String json(Object value) {
		return JSON.writeValueAsString(value);
	}

	/** Text of a body: a string stays as is, any other value becomes JSON text. */
	static String text(AnyValue value) {
		return value.getValueCase() == AnyValue.ValueCase.STRING_VALUE ? value.getStringValue()
				: JSON.writeValueAsString(plain(value));
	}

	static long micros(Instant instant) {
		return Math.multiplyExact(instant.getEpochSecond(), 1_000_000L) + instant.getNano() / 1_000;
	}

	/**
	 * Resource and scope context shared by logs and spans (T041, FR-021): {@code serviceName} from the
	 * {@code service.name} resource attribute when it is a string, {@code resourceAttributes} as JSON text,
	 * and {@code scopeName}/{@code scopeVersion} when present.
	 */
	static void putContext(Map<String, Object> doc, Scoped<?> scoped) {
		for (KeyValue attribute : scoped.resource().getAttributesList()) {
			if (SERVICE_NAME.equals(attribute.getKey())
					&& attribute.getValue().getValueCase() == AnyValue.ValueCase.STRING_VALUE) {
				doc.put("serviceName", attribute.getValue().getStringValue());
			}
		}
		doc.put("resourceAttributes", attributesJson(scoped.resource().getAttributesList()));
		if (!scoped.scope().getName().isEmpty()) {
			doc.put("scopeName", scoped.scope().getName());
		}
		if (!scoped.scope().getVersion().isEmpty()) {
			doc.put("scopeVersion", scoped.scope().getVersion());
		}
	}

	static Map<String, Object> toMap(List<KeyValue> attributes) {
		Map<String, Object> map = new LinkedHashMap<>();
		for (KeyValue attribute : attributes) {
			map.put(attribute.getKey(), plain(attribute.getValue()));
		}
		return map;
	}

	private static Object plain(AnyValue value) {
		return switch (value.getValueCase()) {
			case STRING_VALUE -> value.getStringValue();
			case BOOL_VALUE -> value.getBoolValue();
			case INT_VALUE -> value.getIntValue();
			case DOUBLE_VALUE -> value.getDoubleValue();
			case BYTES_VALUE -> Base64.getEncoder().encodeToString(value.getBytesValue().toByteArray());
			case ARRAY_VALUE -> {
				List<Object> list = new ArrayList<>();
				value.getArrayValue().getValuesList().forEach(v -> list.add(plain(v)));
				yield list;
			}
			case KVLIST_VALUE -> toMap(value.getKvlistValue().getValuesList());
			default -> null;
		};
	}

}
