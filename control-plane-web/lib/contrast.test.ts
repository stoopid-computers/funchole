import { readFileSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";

// Reads the real tokens (app/tokens.css) and checks the colour pairs the app
// actually uses, for both themes: >= 4.5:1 for text, >= 3:1 for control borders.
const css = readFileSync(join(__dirname, "../app/tokens.css"), "utf8");

function block(selector: string) {
  const start = css.indexOf(`${selector} {`);
  const end = css.indexOf("}", start);
  const vars: Record<string, string> = {};
  for (const [, name, value] of css.slice(start, end).matchAll(/--fh-([a-z0-9-]+):\s*(#[0-9a-f]{6})/gi)) vars[name] = value;
  return vars;
}

const light = block(":root");
const themes = { light, dark: { ...light, ...block('[data-theme="dark"]') } };

function luminance(hex: string) {
  const [r, g, b] = [1, 3, 5].map((i) => parseInt(hex.slice(i, i + 2), 16) / 255).map((c) => (c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4));
  return 0.2126 * r + 0.7152 * g + 0.0722 * b;
}

function ratio(a: string, b: string) {
  const [hi, lo] = [luminance(a), luminance(b)].sort((x, y) => y - x);
  return (hi + 0.05) / (lo + 0.05);
}

// [foreground, background] pairs used as text.
const TEXT: [string, string][] = [
  ["ink", "paper"], ["ink", "card"], ["ink", "paper-2"],
  ["ink-2", "paper"], ["ink-2", "card"],
  ["mute", "paper"], ["mute", "card"], ["mute", "paper-2"],
  ["subtle", "paper"], ["subtle", "card"],
  ["blue-d", "paper"], ["blue-d", "card"], ["blue-d", "blue-soft"],
  ["on-blue", "blue"],
  ["live-ink", "live-soft"], ["live-ink", "card"], ["live-ink", "paper"],
  ["danger-ink", "paper"], ["danger-ink", "card"], ["danger-ink", "coral-soft"],
  ["warn-ink", "paper"], ["warn-ink", "card"], ["warn-ink", "sun-soft"],
  ["on-sun", "sun"], ["ink", "sun-soft"],
  ["code-kw", "paper"], ["code-kw", "card"], ["code-num", "paper"], ["code-num", "card"],
  ["island-fg", "island"], ["island-mute", "island"], ["island-code", "island-deep"], ["island-ok", "island"], ["island-bad", "island"],
];

// [border, surface] pairs for form controls.
const CONTROLS: [string, string][] = [["input-line", "paper"], ["input-line", "card"]];

describe.each(Object.entries(themes))("%s theme contrast", (_name, tokens) => {
  it.each(TEXT)("%s on %s is at least 4.5:1", (fg, bg) => {
    expect(tokens[fg], `--fh-${fg}`).toBeDefined();
    expect(tokens[bg], `--fh-${bg}`).toBeDefined();
    expect(ratio(tokens[fg], tokens[bg])).toBeGreaterThanOrEqual(4.5);
  });

  it.each(CONTROLS)("control border %s on %s is at least 3:1", (border, surface) => {
    expect(ratio(tokens[border], tokens[surface])).toBeGreaterThanOrEqual(3);
  });
});
