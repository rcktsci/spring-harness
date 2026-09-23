import MarkdownIt from 'markdown-it';
import DOMPurify from 'dompurify';

const md = new MarkdownIt({
  html: false,
  linkify: true,
  breaks: true,
});

/** Renders markdown and sanitizes the HTML (spec: markdown-it + DOMPurify). */
export function renderMarkdown(text: string): string {
  const html = md.render(text ?? '');
  return DOMPurify.sanitize(html);
}
