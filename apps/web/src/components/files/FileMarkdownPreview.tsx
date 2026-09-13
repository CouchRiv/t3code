import type { ScopedThreadRef } from "@t3tools/contracts";
import { MessageSquareQuote } from "lucide-react";
import { useCallback, useEffect, useRef, useState } from "react";

import ChatMarkdown from "~/components/ChatMarkdown";
import { Button } from "~/components/ui/button";
import { type DraftId, useComposerDraftStore } from "~/composerDraftStore";
import { buildFileReviewComment } from "~/reviewCommentContext";
import { resolvePathLinkTarget } from "~/terminal-links";

export interface FileMarkdownPreviewProps {
  readonly cwd: string;
  readonly relativePath: string;
  readonly text: string;
  readonly threadRef: ScopedThreadRef;
  readonly composerDraftTarget?: ScopedThreadRef | DraftId | undefined;
  readonly onTaskListChange?:
    | ((input: { readonly markerOffset: number; readonly checked: boolean }) => void)
    | undefined;
}

export function FileMarkdownPreview(props: FileMarkdownPreviewProps) {
  const containerRef = useRef<HTMLDivElement | null>(null);
  const addReviewComment = useComposerDraftStore((store) => store.addReviewComment);
  const [floatingAction, setFloatingAction] = useState<{
    text: string;
    top: number;
    left: number;
  } | null>(null);

  const lastSeparator = Math.max(
    props.relativePath.lastIndexOf("/"),
    props.relativePath.lastIndexOf("\\"),
  );
  const imageBaseDir =
    lastSeparator >= 0
      ? resolvePathLinkTarget(props.relativePath.slice(0, lastSeparator), props.cwd)
      : props.cwd;

  const updateSelection = useCallback(() => {
    if (!props.composerDraftTarget || !containerRef.current) {
      setFloatingAction(null);
      return;
    }

    const selection = window.getSelection();
    if (!selection || selection.isCollapsed || selection.rangeCount === 0) {
      setFloatingAction(null);
      return;
    }

    const selectedText = selection.toString().trim();
    if (!selectedText) {
      setFloatingAction(null);
      return;
    }

    const range = selection.getRangeAt(0);
    const container = containerRef.current;
    if (!container.contains(range.commonAncestorContainer)) {
      setFloatingAction(null);
      return;
    }

    const rect = range.getBoundingClientRect();
    const containerRect = container.getBoundingClientRect();

    setFloatingAction({
      text: selectedText,
      top: Math.max(0, rect.top - containerRect.top + container.scrollTop - 42),
      left: Math.max(12, rect.left - containerRect.left + container.scrollLeft + rect.width / 2 - 60),
    });
  }, [props.composerDraftTarget]);

  useEffect(() => {
    const handleDocumentSelectionChange = () => {
      const selection = window.getSelection();
      if (!selection || selection.isCollapsed) {
        setFloatingAction(null);
      }
    };
    document.addEventListener("selectionchange", handleDocumentSelectionChange);
    return () => {
      document.removeEventListener("selectionchange", handleDocumentSelectionChange);
    };
  }, []);

  const handleQuoteInChat = useCallback(() => {
    if (!floatingAction || !props.composerDraftTarget) return;

    const selectedText = floatingAction.text;
    const contents = props.text;
    const index = contents.indexOf(selectedText);
    let startLine = 1;
    let endLine = 1;

    if (index >= 0) {
      const preceding = contents.slice(0, index);
      startLine = preceding.split("\n").length;
      endLine = startLine + selectedText.split("\n").length - 1;
    }

    addReviewComment(
      props.composerDraftTarget,
      buildFileReviewComment({
        id: crypto.randomUUID(),
        filePath: props.relativePath,
        startLine,
        endLine,
        text: selectedText,
        contents,
      }),
    );

    setFloatingAction(null);
    window.getSelection()?.removeAllRanges();
  }, [addReviewComment, floatingAction, props.composerDraftTarget, props.relativePath, props.text]);

  return (
    <div ref={containerRef} className="relative min-h-full w-full" onMouseUp={updateSelection}>
      {floatingAction ? (
        <div
          style={{
            transform: `translate3d(${floatingAction.left}px, ${floatingAction.top}px, 0)`,
          }}
          className="pointer-events-auto absolute left-0 top-0 z-20"
        >
          <Button
            size="xs"
            variant="secondary"
            className="flex items-center gap-1.5 rounded-full border border-border bg-popover/95 px-3 py-1 shadow-lg backdrop-blur hover:bg-accent"
            onMouseDown={(event) => {
              event.preventDefault();
            }}
            onClick={handleQuoteInChat}
          >
            <MessageSquareQuote className="h-3.5 w-3.5 text-primary" />
            <span className="text-xs font-medium">Quote in chat</span>
          </Button>
        </div>
      ) : null}

      <ChatMarkdown
        text={props.text}
        cwd={props.cwd}
        imageBaseDir={imageBaseDir}
        threadRef={props.threadRef}
        className="mx-auto max-w-4xl px-6 py-5"
        onTaskListChange={props.onTaskListChange}
      />
    </div>
  );
}
