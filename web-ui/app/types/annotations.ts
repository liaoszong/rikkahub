export interface UrlCitationAnnotation {
  type: "url_citation";
  title: string;
  url: string;
  sourceId?: string | null;
  citationId?: string | null;
  ordinal?: number | null;
  publisher?: string | null;
  retrievedAt?: number | null;
  startIndex?: number | null;
  endIndex?: number | null;
  textPartOrdinal?: number | null;
  offsetUnit?: string | null;
  quote?: string | null;
  isAvailable?: boolean;
  provenance?: "provider" | "search_tool" | "import" | "legacy_markdown" | string | null;
  providerMetadata?: Record<string, unknown> | null;
}

/**
 * Union type for message annotations
 * @see ai/src/main/java/me/rerere/ai/ui/Message.kt - UIMessageAnnotation
 */
export type UIMessageAnnotation = UrlCitationAnnotation;
