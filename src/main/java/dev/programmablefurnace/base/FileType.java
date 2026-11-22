package dev.programmablefurnace.base;

/**
 * 可编译文件的类型。用户规定：第一个是占位的 {@code BEGIN}，最后一个是占位的 {@code END}，
 * 中间是两个实际类型 —— {@link #DEPENDENCY}（依赖库：依赖文件，成功产物但缺执行函数，内置不可输出，只能删除）
 * 与 {@link #RUNTIME}（运行库：可执行/可输入，玩家创建，可输出）。与产物状态 {@link ContentState} 命名对齐：
 * 依赖库 ↔ 依赖文件(DEPENDENCY)、运行库 ↔ 可执行文件(EXECUTABLE)。
 * BEGIN/END 占位可作范围遍历边界（for (t = BEGIN; t != END; t = next)）后续使用。
 */
public enum FileType {
    BEGIN,       // 占位：类型枚举下界
    DEPENDENCY,  // 依赖库：依赖文件，内置不可输出，只能删除
    RUNTIME,     // 运行库：可执行/可输入文件，玩家创建，可输出
    END;         // 占位：类型枚举上界

    public FileType next() {
        return FileType.values()[this.ordinal() + 1];
    }
}
