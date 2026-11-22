package dev.programmablefurnace.compiler.sem;

import dev.programmablefurnace.compiler.lex.CompileError;
import dev.programmablefurnace.compiler.lex.ErrorCode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 跨模块 import 解析 / 校验（链接期，草稿 §4 M 类错误）。
 * <p>在具备 {@link ModuleRegistry}（全部模块的存储表）时，对被 {@link ImportOptimizer} 判定为
 * 实际引用的 import 做校验：</p>
 * <ul>
 *   <li>M03 找不到模块：import 的目标模块未登记（未引用而砍掉的 import 不校验，§2.2④）；</li>
 *   <li>M04 导入符号不存在：整模块导入引用的成员、或 from 导入的符号在目标模块中不存在；</li>
 *   <li>M05 循环导入：沿模块间依赖图（含未使用 import）DFS 检测环路。</li>
 * </ul>
 * 未引用的 import 按 §2.2④ 被砍掉、不产生错误（即使目标缺失）。单文件 classify 无其他模块，
 * 故本阶段独立于 {@code CompilerAnalyzer}，由链接器在模块齐备时调用。
 */
public final class ImportResolver {

    private final ModuleRegistry registry;
    private final List<CompileError> errors = new ArrayList<>();
    private boolean cycleReported = false;

    private ImportResolver(ModuleRegistry registry) { this.registry = registry; }

    /** 解析当前模块的 import：校验实际引用的导入 + 检测循环导入。 */
    public static ImportResolver resolve(StorageTable current, ImportOptimizer opt, ModuleRegistry registry) {
        ImportResolver r = new ImportResolver(registry);
        r.validateUsedImports(current, opt);
        r.detectCycles(current);
        return r;
    }

    // ===================== M03 / M04 =====================

    private void validateUsedImports(StorageTable current, ImportOptimizer opt) {
        for (StorageTable.ImportEntry imp : current.imports()) {
            ImportOptimizer.ImportUse use = findUse(opt, imp);
            if (use == null) continue;   // 未引用 → 砍掉，不校验

            StorageTable target = registry.resolve(imp.module());
            if (target == null) {
                errors.add(new CompileError(ErrorCode.M03_MODULE_NOT_FOUND, imp.pos(),
                        "找不到模块 '" + imp.module() + "'"));
                continue;
            }
            if (imp.wholeModule()) {
                checkWhole(target, use, imp);
            } else {
                checkSingle(target, use, imp);
            }
        }
    }

    private void checkWhole(StorageTable target, ImportOptimizer.ImportUse use, StorageTable.ImportEntry imp) {
        for (String ref : use.refs()) {
            String member = memberOf(ref);
            if (target.resolve(member) == null) {
                errors.add(new CompileError(ErrorCode.M04_IMPORT_SYMBOL_NOT_FOUND, imp.pos(),
                        "模块 '" + imp.module() + "' 无成员 '" + member + "'"));
            }
        }
    }

    private void checkSingle(StorageTable target, ImportOptimizer.ImportUse use, StorageTable.ImportEntry imp) {
        for (String ref : use.refs()) {
            boolean exists = ref.contains("::")
                    ? target.resolveQualified(ref) != null
                    : target.resolve(ref) != null;
            if (!exists) {
                errors.add(new CompileError(ErrorCode.M04_IMPORT_SYMBOL_NOT_FOUND, imp.pos(),
                        "模块 '" + imp.module() + "' 无符号 '" + ref + "'"));
            }
        }
    }

    private static ImportOptimizer.ImportUse findUse(ImportOptimizer opt, StorageTable.ImportEntry imp) {
        for (ImportOptimizer.ImportUse u : opt.usedImports()) {
            if (u.wholeModule() == imp.wholeModule() && u.module().equals(imp.module())
                    && (imp.wholeModule() || u.symbol().equals(imp.symbol()))) {
                return u;
            }
        }
        return null;
    }

    /** 去掉限定前缀：`lib::X` → `X`。 */
    private static String memberOf(String ref) {
        int i = ref.indexOf("::");
        return i < 0 ? ref : ref.substring(i + 2);
    }

    // ===================== M05 循环导入 =====================

    private void detectCycles(StorageTable current) {
        String start = current.moduleName();
        if (start == null) return;
        Set<String> inPath = new LinkedHashSet<>();
        inPath.add(start);
        Set<String> visited = new HashSet<>();
        visited.add(start);
        dfs(start, inPath, visited);
    }

    private void dfs(String mod, Set<String> inPath, Set<String> visited) {
        StorageTable t = registry.resolve(mod);
        if (t == null) return;
        for (StorageTable.ImportEntry imp : t.imports()) {
            String tgt = imp.module();
            if (inPath.contains(tgt)) {
                reportCycle(tgt, imp);
                continue;
            }
            if (visited.contains(tgt)) continue;
            visited.add(tgt);
            inPath.add(tgt);
            dfs(tgt, inPath, visited);
            inPath.remove(tgt);
        }
    }

    private void reportCycle(String target, StorageTable.ImportEntry imp) {
        if (cycleReported) return;
        cycleReported = true;
        errors.add(new CompileError(ErrorCode.M05_CYCLIC_IMPORT, imp.pos(),
                "模块间形成循环导入，涉及 '" + target + "'"));
    }

    // ===================== 结果 =====================

    public List<CompileError> errors() { return errors; }
}
