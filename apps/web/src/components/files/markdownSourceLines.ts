/**
 * Rendered markdown keeps no trace of the file it came from, so a selection in
 * the preview cannot name the lines it covers. `remarkStampSourceLines` records
 * the source line span of every block on its rendered element, and
 * `resolveSelectionSourceLines` reads that span back off a DOM selection.
 *
 * ChatMarkdown runs the plugin only for callers that pass it through
 * `extraRemarkPlugins`, so chat messages keep the markup they render today.
 */

interface SourceLinePosition {
  readonly line?: number;
}

interface SourceLineAstNode {
  readonly type: string;
  readonly position?: {
    readonly start?: SourceLinePosition;
    readonly end?: SourceLinePosition;
  };
  data?: {
    hProperties?: Record<string, unknown>;
  };
  children?: SourceLineAstNode[];
}

/**
 * Blocks that reach the DOM as an element of their own. Nested types are listed
 * alongside their containers so the innermost block wins when a selection
 * starts inside a list item or a table cell.
 */
const STAMPED_NODE_TYPES = new Set([
  "blockquote",
  "code",
  "definition",
  "footnoteDefinition",
  "heading",
  "html",
  "list",
  "listItem",
  "paragraph",
  "table",
  "tableCell",
  "tableRow",
  "thematicBreak",
]);

export function remarkStampSourceLines() {
  return (tree: SourceLineAstNode) => {
    const visit = (node: SourceLineAstNode) => {
      const startLine = node.position?.start?.line;
      const endLine = node.position?.end?.line;
      if (STAMPED_NODE_TYPES.has(node.type) && startLine !== undefined && endLine !== undefined) {
        const data = (node.data ??= {});
        data.hProperties = {
          ...data.hProperties,
          dataMdStartLine: startLine,
          dataMdEndLine: endLine,
        };
      }
      node.children?.forEach(visit);
    };

    visit(tree);
  };
}

/** Stable identity keeps ChatMarkdown's plugin list from re-rendering the tree. */
export const MARKDOWN_SOURCE_LINE_PLUGINS = [remarkStampSourceLines];

export interface MarkdownSourceLineRange {
  readonly startLine: number;
  readonly endLine: number;
}

function stampedAncestor(node: Node | null, container: HTMLElement): HTMLElement | null {
  const element = node instanceof Element ? node : (node?.parentElement ?? null);
  const stamped = element?.closest<HTMLElement>("[data-md-start-line]") ?? null;
  return stamped && container.contains(stamped) ? stamped : null;
}

function readStampedLine(element: HTMLElement | null, attribute: "mdStartLine" | "mdEndLine") {
  const value = Number(element?.dataset[attribute]);
  return Number.isSafeInteger(value) && value > 0 ? value : null;
}

/**
 * Source lines a rendered selection covers, or null when either end falls
 * outside markdown this preview stamped.
 */
export function resolveSelectionSourceLines(
  range: Range,
  container: HTMLElement,
): MarkdownSourceLineRange | null {
  const startLine = readStampedLine(
    stampedAncestor(range.startContainer, container),
    "mdStartLine",
  );
  const endLine = readStampedLine(stampedAncestor(range.endContainer, container), "mdEndLine");
  if (startLine === null || endLine === null) return null;
  return startLine <= endLine ? { startLine, endLine } : { startLine: endLine, endLine: startLine };
}
