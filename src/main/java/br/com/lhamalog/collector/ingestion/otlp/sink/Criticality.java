// Author: LhamaLog Desenvolvimento (IA) - LLT-182
package br.com.lhamalog.collector.ingestion.otlp.sink;

import java.util.Locale;

/**
 * Criticality from the OTel severity only (LLT-182): {@code severityNumber} ranges, else the
 * {@code severityText} prefix, else {@link #UNSPECIFIED}. Spans have no severity, so they are UNSPECIFIED.
 *
 * <p>A positive number always wins over a conflicting text; a number above 24 (outside OTel) counts as
 * FATAL; the text is read only when the number is 0 or negative, case insensitive, with syslog names
 * ({@code CRIT}, {@code EMERG}, {@code ALERT} as FATAL, {@code NOTICE} as INFO). See research D15.
 */
public enum Criticality {

	UNSPECIFIED("LOW"), TRACE("LOW"), DEBUG("LOW"), INFO("LOW"), WARN("MEDIUM"), ERROR("HIGH"), FATAL("CRITICAL");

	private final String priority;

	Criticality(String priority) {
		this.priority = priority;
	}

	public String priority() {
		return priority;
	}

	public static Criticality of(int severityNumber, String severityText) {
		if (severityNumber >= 21) {
			return FATAL;
		}
		if (severityNumber >= 17) {
			return ERROR;
		}
		if (severityNumber >= 13) {
			return WARN;
		}
		if (severityNumber >= 9) {
			return INFO;
		}
		if (severityNumber >= 5) {
			return DEBUG;
		}
		if (severityNumber >= 1) {
			return TRACE;
		}
		String text = severityText == null ? "" : severityText.strip().toUpperCase(Locale.ROOT);
		if (text.startsWith("FATAL") || text.startsWith("CRIT") || text.startsWith("EMERG") || text.startsWith("ALERT")) {
			return FATAL;
		}
		if (text.startsWith("ERR")) {
			return ERROR;
		}
		if (text.startsWith("WARN")) {
			return WARN;
		}
		if (text.startsWith("INFO") || text.startsWith("NOTICE")) {
			return INFO;
		}
		if (text.startsWith("DEBUG")) {
			return DEBUG;
		}
		if (text.startsWith("TRACE")) {
			return TRACE;
		}
		return UNSPECIFIED;
	}

}
