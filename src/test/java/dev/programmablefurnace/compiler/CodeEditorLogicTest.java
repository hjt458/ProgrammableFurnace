package dev.programmablefurnace.compiler;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * {@link CodeEditorLogic} 纯文本模型单元测试。
 * 覆盖：文本设置、行号索引、光标行列、插入/删除/换行、上下移动、垂直滚动。
 */
class CodeEditorLogicTest {

    // 常规尺寸：高 100，行高 12 → 可见 8 行
    private CodeEditorLogic newLogic() {
        return new CodeEditorLogic(100, 12);
    }

    // ===================== 初始状态 =====================

    @Test
    void initialState() {
        CodeEditorLogic l = newLogic();
        assertThat(l.getValue()).isEmpty();
        assertThat(l.getCursor()).isZero();
        assertThat(l.lineCount()).isEqualTo(1);
        assertThat(l.cursorLine()).isZero();
        assertThat(l.cursorCol()).isZero();
        assertThat(l.getScrollTop()).isZero();
        assertThat(l.maxScrollTop()).isZero();
        assertThat(l.visibleLines()).isEqualTo(8);
    }

    // ===================== setValue =====================

    @Test
    void setValueNullBecomesEmpty() {
        CodeEditorLogic l = newLogic();
        l.setValue(null);
        assertThat(l.getValue()).isEmpty();
        assertThat(l.lineCount()).isEqualTo(1);
    }

    @Test
    void setValueClampsCursor() {
        CodeEditorLogic l = newLogic();
        l.insert("abcdef"); // 光标到末尾 6
        l.setValue("ab");   // 光标 6 > 长度 2 → clamp 到 2
        assertThat(l.getCursor()).isEqualTo(2);
        assertThat(l.getValue()).isEqualTo("ab");
    }

    @Test
    void lineCountWithNewlines() {
        CodeEditorLogic l = newLogic();
        l.setValue("a\nb\nc");
        assertThat(l.lineCount()).isEqualTo(3);
    }

    @Test
    void lineStartIndexes() {
        CodeEditorLogic l = newLogic();
        l.setValue("ab\ncd\ne");
        // 行起始：0="ab", 3="cd", 6="e", 7=末尾
        assertThat(l.lineStart(0)).isZero();
        assertThat(l.lineStart(1)).isEqualTo(3);
        assertThat(l.lineStart(2)).isEqualTo(6);
        assertThat(l.lineStart(3)).isEqualTo(7); // 越界 → 末尾
    }

    // ===================== 光标行列 =====================

    @Test
    void cursorLineColInMiddle() {
        CodeEditorLogic l = newLogic();
        l.setValue("ab\ncd\ne");
        l.setCursor(4); // "cd" 中间，即第 1 行第 1 列
        assertThat(l.cursorLine()).isEqualTo(1);
        assertThat(l.cursorCol()).isEqualTo(1);
    }

    @Test
    void setCursorClampsRange() {
        CodeEditorLogic l = newLogic();
        l.setValue("hello");
        l.setCursor(100);
        assertThat(l.getCursor()).isEqualTo(5);
        l.setCursor(-3);
        assertThat(l.getCursor()).isZero();
    }

    // ===================== insert =====================

    @Test
    void insertAtStartMiddleEnd() {
        CodeEditorLogic l = newLogic();
        l.insert("abc");
        assertThat(l.getValue()).isEqualTo("abc");
        assertThat(l.getCursor()).isEqualTo(3);

        l.setCursor(1);
        l.insert("X");
        assertThat(l.getValue()).isEqualTo("aXbc");
        assertThat(l.getCursor()).isEqualTo(2);

        l.setCursor(4);
        l.insert("Z");
        assertThat(l.getValue()).isEqualTo("aXbcZ"); // 光标 4 在末尾，Z 加在末尾
        assertThat(l.getCursor()).isEqualTo(5);
    }

    @Test
    void insertEmptyOrNullIsNoOp() {
        CodeEditorLogic l = newLogic();
        l.insert("");
        l.insert(null);
        assertThat(l.getValue()).isEmpty();
        assertThat(l.getCursor()).isZero();
    }

    @Test
    void insertNewlineSplitsLine() {
        CodeEditorLogic l = newLogic();
        l.insert("ab");
        l.setCursor(1);
        l.insert("\n");
        assertThat(l.getValue()).isEqualTo("a\nb");
        assertThat(l.lineCount()).isEqualTo(2);
        assertThat(l.cursorLine()).isEqualTo(1); // 光标在新行
        assertThat(l.cursorCol()).isZero();
    }

    // ===================== delete =====================

    @Test
    void deleteBefore() {
        CodeEditorLogic l = newLogic();
        l.insert("abc");
        l.setCursor(2);
        l.deleteBefore(); // 删 'b'
        assertThat(l.getValue()).isEqualTo("ac");
        assertThat(l.getCursor()).isEqualTo(1);
    }

    @Test
    void deleteBeforeAtStartIsNoOp() {
        CodeEditorLogic l = newLogic();
        l.insert("abc");
        l.setCursor(0);
        l.deleteBefore();
        assertThat(l.getValue()).isEqualTo("abc");
        assertThat(l.getCursor()).isZero();
    }

    @Test
    void deleteAfter() {
        CodeEditorLogic l = newLogic();
        l.insert("abc");
        l.setCursor(1);
        l.deleteAfter(); // 删 'b'
        assertThat(l.getValue()).isEqualTo("ac");
        assertThat(l.getCursor()).isEqualTo(1);
    }

    @Test
    void deleteAfterAtEndIsNoOp() {
        CodeEditorLogic l = newLogic();
        l.insert("abc");
        l.setCursor(3);
        l.deleteAfter();
        assertThat(l.getValue()).isEqualTo("abc");
        assertThat(l.getCursor()).isEqualTo(3);
    }

    @Test
    void deleteBeforeJoinsLines() {
        CodeEditorLogic l = newLogic();
        l.setValue("ab\ncd");
        l.setCursor(3); // 第 1 行开头
        l.deleteBefore(); // 删换行 → 合并
        assertThat(l.getValue()).isEqualTo("abcd");
        assertThat(l.lineCount()).isEqualTo(1);
    }

    // ===================== moveLine =====================

    @Test
    void moveLineDownKeepsColumn() {
        CodeEditorLogic l = newLogic();
        l.setValue("ab\ncdef\ngh");
        l.setCursor(1); // 第 0 行第 1 列
        l.setDesiredCol(1);
        l.moveLine(1);
        assertThat(l.cursorLine()).isEqualTo(1);
        assertThat(l.cursorCol()).isEqualTo(1); // 保持目标列
        assertThat(l.getValue().charAt(l.getCursor())).isEqualTo('d');
    }

    @Test
    void moveLineClampsToLineLength() {
        CodeEditorLogic l = newLogic();
        l.setValue("ab\nc\nlongline");
        l.setCursor(1); // 第 0 行列 1
        l.setDesiredCol(1);
        l.moveLine(1);  // 到第 1 行 "c"(长度 1) → 目标列 1 越界，光标停行尾（列=行长度 1）
        assertThat(l.cursorLine()).isEqualTo(1);
        assertThat(l.cursorCol()).isEqualTo(1);
        l.moveLine(1);  // desiredCol 已同步为 1 → 第 2 行 "longline" 保持列 1
        assertThat(l.cursorLine()).isEqualTo(2);
        assertThat(l.getCursor()).isEqualTo(6); // "longline" 起始 5 + 列 1
    }

    @Test
    void moveLineBeyondEdgesNoOp() {
        CodeEditorLogic l = newLogic();
        l.setValue("a\nb");
        l.setCursor(0);
        l.moveLine(-1); // 已在第 0 行
        assertThat(l.cursorLine()).isZero();
        l.setCursor(3);
        l.moveLine(1); // 已在最后一行
        assertThat(l.cursorLine()).isEqualTo(1);
    }

    // ===================== 滚动 =====================

    @Test
    void visibleLinesAtLeastOne() {
        CodeEditorLogic l = new CodeEditorLogic(10, 12); // 0 行可见
        assertThat(l.visibleLines()).isEqualTo(1);
    }

    @Test
    void maxScrollTopForLongContent() {
        CodeEditorLogic l = newLogic(); // 可见 8 行
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 12; i++) sb.append("line").append(i).append('\n');
        l.setValue(sb.toString()); // 12 个 '\n' → 13 行
        assertThat(l.lineCount()).isEqualTo(13);
        assertThat(l.maxScrollTop()).isEqualTo(13 - 8); // 5
        assertThat(l.getScrollTop()).isZero();
    }

    @Test
    void scrollToCursorScrollsDown() {
        CodeEditorLogic l = newLogic(); // 可见 8 行
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 12; i++) sb.append("line").append(i).append('\n');
        l.setValue(sb.toString()); // 13 行
        l.setCursor(sb.length()); // 光标末尾 → 第 12 行
        l.scrollToCursor();
        assertThat(l.getScrollTop()).isEqualTo(12 - 8 + 1); // 5
    }

    @Test
    void scrollToCursorScrollsUp() {
        CodeEditorLogic l = newLogic();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 12; i++) sb.append("line").append(i).append('\n');
        l.setValue(sb.toString()); // 13 行
        l.setScrollTop(5); // 手动滚到底
        l.setCursor(0);    // 光标回到第 0 行
        l.scrollToCursor();
        assertThat(l.getScrollTop()).isZero();
    }

    @Test
    void setScrollTopClamps() {
        CodeEditorLogic l = newLogic();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 20; i++) sb.append("x\n");
        l.setValue(sb.toString()); // 21 行，可见 8 → max=13
        l.setScrollTop(999);
        assertThat(l.getScrollTop()).isEqualTo(13);
        l.setScrollTop(-5);
        assertThat(l.getScrollTop()).isZero();
    }

    // ===================== rowOf =====================

    @Test
    void rowOfExcludesNewline() {
        CodeEditorLogic l = newLogic();
        l.setValue("ab\ncd\ne");
        assertThat(l.rowOf(0)).isEqualTo("ab");
        assertThat(l.rowOf(1)).isEqualTo("cd");
        assertThat(l.rowOf(2)).isEqualTo("e");
    }

    @Test
    void insertAndMoveLineKeepDesiredCol() {
        CodeEditorLogic l = newLogic();
        l.setValue("abcdef\nghijkl");
        l.setCursor(3); // 列 3
        l.setDesiredCol(3);
        l.moveLine(1);
        assertThat(l.cursorCol()).isEqualTo(3);
        // 左右移动后 desiredCol 更新
        l.setDesiredCol(1);
        l.moveLine(-1);
        assertThat(l.cursorCol()).isEqualTo(1);
    }

    // ===================== 外部输入规范化（粘贴） =====================

    @Test
    void normalizeInserted_stripsCarriageReturns() {
        assertThat(CodeEditorLogic.normalizeInserted("a\r\nb\rc")).isEqualTo("a\nb\nc");
    }

    @Test
    void normalizeInserted_expandsTabsToFourSpaces() {
        assertThat(CodeEditorLogic.normalizeInserted("if (x) {\n\treturn;\n}"))
                .isEqualTo("if (x) {\n    return;\n}");
    }

    @Test
    void normalizeInserted_handlesNullOrEmpty() {
        assertThat(CodeEditorLogic.normalizeInserted(null)).isEmpty();
        assertThat(CodeEditorLogic.normalizeInserted("")).isEmpty();
    }

    @Test
    void insert_pastedWindowsText_keepsLineCountCorrect() {
        CodeEditorLogic l = newLogic();
        l.insert("int a;\r\nint b;\r\n");

        assertThat(l.getValue()).isEqualTo("int a;\nint b;\n");
        assertThat(l.lineCount()).isEqualTo(3);
        assertThat(l.getValue()).doesNotContain("\r");
    }
}
