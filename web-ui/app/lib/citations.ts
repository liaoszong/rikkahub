import type { UIMessageAnnotation, UrlCitationAnnotation } from "~/types";

export function citationIdentity(annotation: UrlCitationAnnotation): string {
  return annotation.sourceId || annotation.url || annotation.citationId || annotation.title;
}

export function getSafeCitationUrl(raw: string): string | null {
  if (!raw || raw.length > 8 * 1024) return null;
  try {
    const parsed = new URL(raw);
    if (
      (parsed.protocol !== "http:" && parsed.protocol !== "https:") ||
      !parsed.hostname ||
      parsed.username ||
      parsed.password
    ) {
      return null;
    }
    for (const [key, value] of Array.from(parsed.searchParams.entries())) {
      if (isSensitiveCitationKey(key) || containsCredentialMaterial(value)) {
        parsed.searchParams.delete(key);
      }
    }
    parsed.hash = "";
    return parsed.href;
  } catch {
    return null;
  }
}

export function getSafeCitationDisplayText(raw: string | null | undefined): string | null {
  const trimmed = raw?.trim();
  const decoded = trimmed ? decodeCitationComponent(trimmed) : "";
  if (
    !trimmed ||
    containsCredentialMaterial(trimmed) ||
    containsSemanticSecretEntry(trimmed) ||
    containsCredentialMaterial(decoded) ||
    containsSemanticSecretEntry(decoded)
  ) {
    return null;
  }
  if (/^https?:\/\//i.test(trimmed)) return getSafeCitationUrl(trimmed);
  return trimmed;
}

function decodeCitationComponent(value: string): string {
  let decoded = value;
  for (let attempt = 0; attempt < 3; attempt += 1) {
    try {
      const next = decodeURIComponent(decoded.replaceAll("+", " "));
      if (next === decoded) return decoded;
      decoded = next;
    } catch {
      return decoded;
    }
  }
  return decoded;
}

function isSensitiveCitationKey(key: string): boolean {
  const normalized = key.toLowerCase().replaceAll(/[^a-z0-9]/g, "");
  return (
    SENSITIVE_CITATION_KEYS.has(normalized) ||
    normalized.endsWith("token") ||
    normalized.endsWith("secret") ||
    normalized.endsWith("password") ||
    normalized.endsWith("passwd") ||
    normalized.endsWith("credential") ||
    normalized.endsWith("signature") ||
    normalized.includes("apikey")
  );
}

function containsCredentialMaterial(value: string): boolean {
  return (
    /\bbearer(?:\s+|%20|\+)[^\s,;"']+/i.test(value) ||
    /\b(?:authorization|proxy[-_ ]?authorization|api[-_ ]?key|x[-_ ]?api[-_ ]?key|access[-_ ]?token|refresh[-_ ]?token|session[-_ ]?token|token|secret|password|passwd|signature|x[-_ ]?amz[-_ ]?signature|x[-_ ]?goog[-_ ]?signature)["']?\s*[:=]\s*[^\s,;]+/i.test(
      value,
    )
  );
}

function containsSemanticSecretEntry(value: string): boolean {
  const match = value.match(
    /["']?(?:name|header|headerName|key|parameter|parameterName)["']?\s*:\s*["']([^"']+)["']/i,
  );
  if (!match) return false;
  const normalized = match[1].toLowerCase().replaceAll(/[^a-z0-9]/g, "");
  const hasSemanticValue = /["']?(?:value|values)["']?\s*:/i.test(value);
  return (
    isSensitiveCitationKey(match[1]) ||
    (hasSemanticValue && !SAFE_HEADER_VALUE_NAMES.has(normalized))
  );
}

const SAFE_HEADER_VALUE_NAMES = new Set(["accept", "acceptencoding", "contenttype", "useragent"]);

const SENSITIVE_CITATION_KEYS = new Set([
  "authorization",
  "auth",
  "bearer",
  "key",
  "apikey",
  "xapikey",
  "accesstoken",
  "refreshtoken",
  "token",
  "secret",
  "password",
  "passwd",
  "clientsecret",
  "privatekey",
  "accesskey",
  "secretkey",
  "credential",
  "signature",
  "sig",
  "session",
  "sessionid",
  "jwt",
  "code",
  "xgoogsignature",
  "xamzsignature",
  "xamzcredential",
  "xamzsecuritytoken",
]);

export function distinctUrlCitations(
  annotations: UIMessageAnnotation[] | undefined,
): UrlCitationAnnotation[] {
  const seen = new Set<string>();
  return (annotations ?? []).filter((annotation): annotation is UrlCitationAnnotation => {
    if (annotation.type !== "url_citation") return false;
    const identity = citationIdentity(annotation);
    if (!identity || seen.has(identity)) return false;
    seen.add(identity);
    return true;
  });
}

export function citationPresentation(annotation: UrlCitationAnnotation): {
  label: string;
  safeUrl: string | null;
} {
  const safeUrl = annotation.isAvailable === false ? null : getSafeCitationUrl(annotation.url);
  const label =
    annotation.isAvailable === false
      ? "Source unavailable"
      : getSafeCitationDisplayText(annotation.title) ||
        getSafeCitationDisplayText(annotation.publisher) ||
        safeUrl ||
        "Source unavailable";
  return { label, safeUrl };
}

export function appendCitationsToPlainText(
  text: string,
  annotations: UIMessageAnnotation[] | undefined,
): string {
  const citations = distinctUrlCitations(annotations);
  if (citations.length === 0) return text.trim();
  const sources = citations.map((citation, index) => {
    const { label, safeUrl } = citationPresentation(citation);
    return `[${index + 1}] ${label}${safeUrl ? ` — ${safeUrl}` : ""}`;
  });
  const body = text.trim();
  return `${body ? `${body}\n\n` : ""}Sources:\n${sources.join("\n")}`;
}

export function escapeMarkdownLabel(value: string): string {
  return value
    .replaceAll("\\", "\\\\")
    .replaceAll("[", "\\[")
    .replaceAll("]", "\\]")
    .replace(/[\r\n]+/g, " ");
}
