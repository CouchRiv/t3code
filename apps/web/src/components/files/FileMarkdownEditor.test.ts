import { AutoLinkNode, LinkNode } from "@lexical/link";
import { ListItemNode, ListNode } from "@lexical/list";
import { CodeHighlightNode, CodeNode } from "@lexical/code";
import { $convertFromMarkdownString, $convertToMarkdownString } from "@lexical/markdown";
import { HorizontalRuleNode } from "@lexical/react/LexicalHorizontalRuleNode";
import { HeadingNode, QuoteNode } from "@lexical/rich-text";
import { createEditor } from "lexical";
import { describe, expect, it } from "vite-plus/test";

import { MARKDOWN_EDITOR_TRANSFORMERS } from "./FileMarkdownEditor";

describe("FileMarkdownEditor transformers", () => {
  function createTestEditor() {
    return createEditor({
      nodes: [
        HeadingNode,
        QuoteNode,
        ListNode,
        ListItemNode,
        CodeNode,
        CodeHighlightNode,
        AutoLinkNode,
        LinkNode,
        HorizontalRuleNode,
      ],
    });
  }

  it("converts headings, quotes, and text styles to and from markdown", () => {
    const editor = createTestEditor();
    const markdown =
      "# Heading 1\n\n## Heading 2\n\n> Blockquote\n\n**Bold** and *italic* and `code`.";

    editor.update(
      () => {
        $convertFromMarkdownString(markdown, MARKDOWN_EDITOR_TRANSFORMERS);
      },
      { discrete: true },
    );

    editor.getEditorState().read(() => {
      const exported = $convertToMarkdownString(MARKDOWN_EDITOR_TRANSFORMERS);
      expect(exported).toContain("# Heading 1");
      expect(exported).toContain("## Heading 2");
      expect(exported).toContain("> Blockquote");
      expect(exported).toContain("**Bold**");
      expect(exported).toContain("*italic*");
      expect(exported).toContain("`code`");
    });
  });

  it("converts task lists (checklists) to and from markdown", () => {
    const editor = createTestEditor();
    const markdown = "- [ ] Unchecked task\n- [x] Completed task";

    editor.update(
      () => {
        $convertFromMarkdownString(markdown, MARKDOWN_EDITOR_TRANSFORMERS);
      },
      { discrete: true },
    );

    editor.getEditorState().read(() => {
      const exported = $convertToMarkdownString(MARKDOWN_EDITOR_TRANSFORMERS);
      expect(exported).toBe("- [ ] Unchecked task\n- [x] Completed task");
    });
  });

  it("converts code blocks with language specification", () => {
    const editor = createTestEditor();
    const markdown = '```typescript\nconst greeting = "hello";\n```';

    editor.update(
      () => {
        $convertFromMarkdownString(markdown, MARKDOWN_EDITOR_TRANSFORMERS);
      },
      { discrete: true },
    );

    editor.getEditorState().read(() => {
      const exported = $convertToMarkdownString(MARKDOWN_EDITOR_TRANSFORMERS);
      expect(exported).toContain("```typescript");
      expect(exported).toContain('const greeting = "hello";');
    });
  });

  it("converts horizontal rules to and from markdown", () => {
    const editor = createTestEditor();
    const markdown = "Section Above\n\n---\n\nSection Below";

    editor.update(
      () => {
        $convertFromMarkdownString(markdown, MARKDOWN_EDITOR_TRANSFORMERS);
      },
      { discrete: true },
    );

    editor.getEditorState().read(() => {
      const exported = $convertToMarkdownString(MARKDOWN_EDITOR_TRANSFORMERS);
      expect(exported).toContain("---");
      expect(exported).toContain("Section Above");
      expect(exported).toContain("Section Below");
    });
  });

  it("converts markdown links and auto-links", () => {
    const editor = createTestEditor();
    const markdown = "[T3 Tools](https://t3.gg)";

    editor.update(
      () => {
        $convertFromMarkdownString(markdown, MARKDOWN_EDITOR_TRANSFORMERS);
      },
      { discrete: true },
    );

    editor.getEditorState().read(() => {
      const exported = $convertToMarkdownString(MARKDOWN_EDITOR_TRANSFORMERS);
      expect(exported).toBe("[T3 Tools](https://t3.gg)");
    });
  });
});
