package dev.programmablefurnace.compiler.exec;

import java.util.List;

/**
 * 一次 tick（执行一次 RunLoop）的结果（草稿 §3.8）。
 * <ul>
 *   <li>{@link Status#OK} 正常执行完毕；</li>
 *   <li>{@link Status#NO_ENTRY} 模块无可执行入口（不含 RunLoop，属依赖库文件，§4.5）；</li>
 *   <li>{@link Status#RUNTIME_ERROR} 运行期出错（未声明变量、非法运算、机器原语异常等）。
 *       运行期错误不归入编译失败（§4.6），由执行器/上层按需处理。</li>
 * </ul>
 */
public final class ExecResult {

    public enum Status { OK, NO_ENTRY, RUNTIME_ERROR }

    private final Status status;
    private final String message;
    private final SourcePosHolder pos;   // 出错位置（可选）

    private ExecResult(Status status, String message, SourcePosHolder pos) {
        this.status = status;
        this.message = message;
        this.pos = pos;
    }

    public static ExecResult success() { return new ExecResult(Status.OK, null, null); }

    public static ExecResult noEntry(String module) {
        return new ExecResult(Status.NO_ENTRY, "模块无执行入口 RunLoop: " + module, null);
    }

    public static ExecResult error(String message) { return new ExecResult(Status.RUNTIME_ERROR, message, null); }

    public static ExecResult error(String message, dev.programmablefurnace.compiler.lex.SourcePos p) {
        return new ExecResult(Status.RUNTIME_ERROR, message, new SourcePosHolder(p.fileName(), p.line(), p.col()));
    }

    public Status status() { return status; }
    public String message() { return message; }
    public boolean ok() { return status == Status.OK; }

    /** 简化的位置持有者，避免 ExecResult 与词法层直接耦合展示。 */
    public record SourcePosHolder(String file, int line, int col) {
        @Override public String toString() {
            return (file == null ? "" : file + ":") + line + ":" + col;
        }
    }

    @Override
    public String toString() {
        return status + (message == null ? "" : " " + message)
                + (pos == null ? "" : " @ " + pos);
    }
}
