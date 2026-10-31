// Author: LhamaLog Desenvolvimento (IA) - LLT-181
package br.com.lhamalog.collector.ingestion.otlp.sink;

import com.google.protobuf.ByteString;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.KeyValue;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;

/** Conversions from OTLP values to the flat JSON documents written to OpenObserve. */
final class OtlpValues {

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

	/** Text of a body: a string stays as is, any other value becomes JSON text. */
	static String text(AnyValue value) {
		return value.getValueCase() == AnyValue.ValueCase.STRING_VALUE ? value.getStringValue()
				: JSON.writeValueAsString(plain(value));
	}

	private static Map<String, Object> toMap(List<KeyValue> attributes) {
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
