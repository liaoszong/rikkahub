import assert from "node:assert/strict";
import test from "node:test";

import type { MessageDto, UrlCitationAnnotation } from "~/types";
import { appendCitationsToPlainText, citationPresentation, getSafeCitationUrl } from "./citations";
import { convertMessageToMarkdown } from "./export-markdown";

const unsafeCitation: UrlCitationAnnotation = {
  type: "url_citation",
  title: "Authorization%3A%20Bearer%20title-secret",
  publisher: "Safe Publisher",
  url: "https://example.com/source?key=url-secret&continuation=Bearer%20query-secret&lang=zh#token",
  providerMetadata: {
    Authorization: "Bearer metadata-secret",
    headers: [{ name: "Authorization", value: "Bearer semantic-secret" }],
  },
};

test("citation URL removes signed keys and credential-bearing values", () => {
  assert.equal(getSafeCitationUrl(unsafeCitation.url), "https://example.com/source?lang=zh");
});

test("citation presentation rejects credential-bearing labels", () => {
  assert.deepEqual(citationPresentation(unsafeCitation), {
    label: "Safe Publisher",
    safeUrl: "https://example.com/source?lang=zh",
  });
});

test("web copy and markdown export contain only safe citation fields", () => {
  const message: MessageDto = {
    id: "message-1",
    role: "ASSISTANT",
    parts: [{ type: "text", text: "Safe answer" }],
    annotations: [unsafeCitation],
    createdAt: "2026-08-05T00:00:00Z",
  };
  const clipboard = appendCitationsToPlainText("Safe answer", message.annotations);
  const markdown = convertMessageToMarkdown(message, false);

  for (const outbound of [clipboard, markdown]) {
    assert.match(outbound, /Safe Publisher/);
    assert.match(outbound, /https:\/\/example\.com\/source\?lang=zh/);
    assert.doesNotMatch(
      outbound,
      /Authorization|Bearer|url-secret|query-secret|metadata-secret|semantic-secret/i,
    );
  }
});
