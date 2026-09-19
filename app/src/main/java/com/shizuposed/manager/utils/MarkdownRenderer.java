package com.shizuposed.manager.utils;

import android.content.Context;
import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.BulletSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.LeadingMarginSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.text.style.URLSpan;
import android.util.TypedValue;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minimal Markdown to Spannable renderer.
 *
 * Not a CommonMark implementation. Covers the subset that appears
 * in real Xposed module READMEs: headings, bold, italic, inline
 * code, fenced code blocks, links, unordered and ordered lists,
 * blockquotes, and horizontal rules.
 *
 * Anything unrecognized is left as plain text. The renderer never
 * throws — a malformed input still produces readable output.
 *
 * Design choices:
 *   • Code blocks are rendered monospace with a subtle background,
 *     not syntax-highlighted. Highlighting requires a lexer and a
 *     theme; both are out of scope.
 *   • Links are rendered as URLSpan with the link text visible, but
 *     no automatic styling beyond a primary color. The click handler
 *     lives in the view layer, not here.
 *   • Nested formatting (bold inside italic, etc.) is applied as
 *     overlapping spans. Android's Spanned handles this correctly
 *     for the style types we use.
 */
public final class MarkdownRenderer {

    private MarkdownRenderer() {}

    /** Render Markdown to a SpannableStringBuilder. Never null. */
    public static CharSequence render(Context context, String markdown) {
        SpannableStringBuilder out = new SpannableStringBuilder();
        if (markdown == null || markdown.isEmpty()) return out;

        String[] lines = markdown.replace("\r\n", "\n").split("\n", -1);
        boolean inCodeFence = false;
        StringBuilder codeBuf = new StringBuilder();

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];

            // Fenced code block delimiter
            if (line.trim().startsWith("```")) {
                if (inCodeFence) {
                    // Close: append the buffered code as a styled block
                    appendCodeBlock(out, codeBuf.toString(), context);
                    codeBuf.setLength(0);
                    inCodeFence = false;
                } else {
                    inCodeFence = true;
                }
                continue;
            }

            if (inCodeFence) {
                codeBuf.append(line).append('\n');
                continue;
            }

            // Horizontal rule
            if (line.matches("^\\s*([-*_])(\\s*\\1){2,}\\s*$")) {
                out.append("\n");
                int start = out.length();
                out.append("─────────────────────");
                out.setSpan(new ForegroundColorSpan(0x33888888),
                    start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                out.append("\n");
                continue;
            }

            // Heading
            Matcher h = Pattern.compile("^(#{1,6})\\s+(.*)$").matcher(line);
            if (h.matches()) {
                int level = h.group(1).length();
                String text = h.group(2).trim();
                int start = out.length();
                out.append(text);
                int end = out.length();
                float scale = level == 1 ? 1.5f
                    : level == 2 ? 1.3f
                    : level == 3 ? 1.15f
                    : 1.05f;
                out.setSpan(new RelativeSizeSpan(scale), start, end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                out.setSpan(new StyleSpan(Typeface.BOLD), start, end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                out.append("\n");
                continue;
            }

            // Blockquote
            if (line.trim().startsWith(">")) {
                String text = line.replaceFirst("^\\s*>\\s?", "");
                int start = out.length();
                out.append("▎ ");
                int textStart = out.length();
                appendInline(out, text);
                out.setSpan(new LeadingMarginSpan.Standard(dp(context, 12), 0),
                    start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                out.setSpan(new ForegroundColorSpan(0x88AAAAAA),
                    start, textStart, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                out.append("\n");
                continue;
            }

            // Unordered list
            Matcher ul = Pattern.compile("^\\s*([-*+])\\s+(.*)$").matcher(line);
            if (ul.matches()) {
                int start = out.length();
                out.append(ul.group(2));
                out.setSpan(new BulletSpan(dp(context, 16)),
                    start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                appendInlineToRange(out, start, out.length());
                out.append("\n");
                continue;
            }

            // Ordered list
            Matcher ol = Pattern.compile("^\\s*(\\d+)\\.\\s+(.*)$").matcher(line);
            if (ol.matches()) {
                String num = ol.group(1) + ". ";
                int start = out.length();
                out.append(num);
                int textStart = out.length();
                out.append(ol.group(2));
                out.setSpan(new LeadingMarginSpan.Standard(dp(context, 20)),
                    start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                appendInlineToRange(out, textStart, out.length());
                out.append("\n");
                continue;
            }

            // Empty line
            if (line.trim().isEmpty()) {
                out.append("\n");
                continue;
            }

            // Paragraph
            int start = out.length();
            appendInline(out, line);
            out.append("\n");
        }

        // Unclosed code fence
        if (inCodeFence && codeBuf.length() > 0) {
            appendCodeBlock(out, codeBuf.toString(), context);
        }

        return out;
    }

    // ── inline formatting ─────────────────────────────────────────

    private static void appendInline(SpannableStringBuilder out, String text) {
        int start = out.length();
        out.append(text);
        appendInlineToRange(out, start, out.length());
    }

    /**
     * Apply bold, italic, inline-code, and link spans to the given
     * range of `out`. Called after the raw text has been appended.
     */
    private static void appendInlineToRange(SpannableStringBuilder out,
                                            int rangeStart, int rangeEnd) {
        String text = out.subSequence(rangeStart, rangeEnd).toString();

        // Links: [text](url)
        Matcher link = Pattern.compile("\\[([^\\]]+)\\]\\(([^)]+)\\)").matcher(text);
        while (link.find()) {
            int s = rangeStart + link.start(1);
            int e = rangeStart + link.end(1);
            String url = link.group(2).trim();
            out.setSpan(new URLSpan(url), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }

        // Inline code: `text`
        Matcher ic = Pattern.compile("`([^`]+)`").matcher(text);
        while (ic.find()) {
            int s = rangeStart + ic.start(1);
            int e = rangeStart + ic.end(1);
            out.setSpan(new TypefaceSpan("monospace"), s, e,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            out.setSpan(new BackgroundColorSpan(0x22888888), s, e,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }

        // Bold: **text** or __text__
        Matcher bold = Pattern.compile("(\\*\\*|__)(.+?)\\1").matcher(text);
        while (bold.find()) {
            int s = rangeStart + bold.start(2);
            int e = rangeStart + bold.end(2);
            out.setSpan(new StyleSpan(Typeface.BOLD), s, e,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }

        // Italic: *text* or _text_, avoiding the bold cases
        Matcher ital = Pattern.compile("(?<!\\*)\\*(?!\\*)(.+?)(?<!\\*)\\*(?!\\*)").matcher(text);
        while (ital.find()) {
            int s = rangeStart + ital.start(1);
            int e = rangeStart + ital.end(1);
            out.setSpan(new StyleSpan(Typeface.ITALIC), s, e,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        Matcher ital2 = Pattern.compile("(?<!_)_(?!_)(.+?)(?<!_)_(?!_)").matcher(text);
        while (ital2.find()) {
            int s = rangeStart + ital2.start(1);
            int e = rangeStart + ital2.end(1);
            out.setSpan(new StyleSpan(Typeface.ITALIC), s, e,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

    private static void appendCodeBlock(SpannableStringBuilder out,
                                        String code, Context context) {
        if (code.isEmpty()) return;
        out.append("\n");
        int start = out.length();
        out.append(code);
        if (!code.endsWith("\n")) out.append("\n");
        int end = out.length();
        out.setSpan(new TypefaceSpan("monospace"), start, end,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        out.setSpan(new BackgroundColorSpan(0x22888888), start, end,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        out.append("\n");
    }

    private static int dp(Context context, int value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP,
            value, context.getResources().getDisplayMetrics());
    }
}