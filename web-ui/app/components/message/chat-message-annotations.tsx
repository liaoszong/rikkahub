import * as React from "react";

import { ExternalLink } from "lucide-react";

import { citationPresentation, distinctUrlCitations } from "~/lib/citations";
import { cn } from "~/lib/utils";
import type { UIMessageAnnotation } from "~/types";

export function ChatMessageAnnotationsRow({
  annotations,
  alignRight,
}: {
  annotations?: UIMessageAnnotation[];
  alignRight: boolean;
}) {
  const citations = React.useMemo(() => distinctUrlCitations(annotations), [annotations]);

  if (citations.length === 0) {
    return null;
  }

  return (
    <div
      className={cn(
        "flex w-full flex-wrap items-center gap-2 px-1",
        alignRight ? "justify-end" : "justify-start",
      )}
    >
      {citations.map((annotation, index) => {
        const { label, safeUrl } = citationPresentation(annotation);

        return safeUrl ? (
          <a
            key={annotation.citationId ?? `${annotation.url}-${index}`}
            href={safeUrl}
            target="_blank"
            rel="noopener noreferrer"
            className="inline-flex max-w-full items-center gap-1 rounded-full border border-border bg-background px-2 py-1 text-xs text-muted-foreground transition-colors hover:border-primary/40 hover:text-foreground"
            title={safeUrl}
          >
            <span className="max-w-[220px] truncate">{label}</span>
            <ExternalLink className="size-3" />
          </a>
        ) : (
          <span
            key={annotation.citationId ?? annotation.sourceId ?? `unavailable-${index}`}
            className="inline-flex max-w-full items-center rounded-full border border-border bg-muted px-2 py-1 text-xs text-muted-foreground"
          >
            <span className="max-w-[220px] truncate">{label || "Source unavailable"}</span>
          </span>
        );
      })}
    </div>
  );
}
