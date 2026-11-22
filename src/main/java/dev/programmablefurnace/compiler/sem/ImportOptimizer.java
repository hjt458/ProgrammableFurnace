package dev.programmablefurnace.compiler.sem;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 编译优化：import 以最小单位登记（草稿 §2.2 ④、§2.4）。
 * <p>分析文件中实际被引用的导入符号，只保留用到的部分登记进存储表，砍掉未引用的
 * （类似 C/C++ 链接期只保留被引用符号 / dead code elimination）。引用来源为
 * {@link SymbolBinder#referencedExternals()} 与 {@link SymbolBinder#referencedSymbols()}。</p>
 * <ul>
 *   <li>全量 {@code import 模块;}：按 {@code 模块.成员} 使用（草稿 §3.5），实际引用的成员
 *       = 前缀 {@code 模块::} 的限定引用；无引用则整个 import 视为未用。</li>
 *   <li>精确 {@code from 模块 import 符号;}：裸名直接引用，或作为命名空间前缀
 *       {@code 符号::成员} 使用；无引用则砍掉。</li>
 * </ul>
 * 最小登记集合见 {@link #minimalImportSet()}（实际需从导入模块登记进存储表的符号）。
 */
public final class ImportOptimizer {

    /** 一条保留的导入，及其实际被引用的符号（module::member 或裸符号名）。 */
    public record ImportUse(String module, String symbol, boolean wholeModule, List<String> refs) {}

    private final List<ImportUse> used = new ArrayList<>();
    private final List<StorageTable.ImportEntry> unused = new ArrayList<>();

    private ImportOptimizer() {}

    /** 对存储表与绑定结果执行 import 最小单位登记。 */
    public static ImportOptimizer optimize(StorageTable table, SymbolBinder binder) {
        ImportOptimizer o = new ImportOptimizer();
        for (StorageTable.ImportEntry imp : table.imports()) {
            if (imp.wholeModule()) o.processWhole(binder, imp);
            else o.processSingle(binder, imp);
        }
        return o;
    }

    private void processWhole(SymbolBinder binder, StorageTable.ImportEntry imp) {
        String prefix = imp.module() + "::";
        List<String> refs = new ArrayList<>();
        for (String ext : binder.referencedExternals()) {
            if (ext.startsWith(prefix)) refs.add(ext);
        }
        if (refs.isEmpty()) unused.add(imp);
        else used.add(new ImportUse(imp.module(), null, true, refs));
    }

    private void processSingle(SymbolBinder binder, StorageTable.ImportEntry imp) {
        String sym = imp.symbol();
        List<String> refs = new ArrayList<>();
        if (binder.referencedExternals().contains(sym)) refs.add(sym);
        String prefix = sym + "::";
        for (String ext : binder.referencedExternals()) {
            if (ext.startsWith(prefix)) refs.add(ext);
        }
        // 可能被本地命名空间遮蔽（referencedSymbols 而非 externals）：此时导入确未使用
        if (refs.isEmpty()) unused.add(imp);
        else used.add(new ImportUse(imp.module(), sym, false, refs));
    }

    // ===================== 结果 =====================

    /** 被实际引用、予以保留的导入。 */
    public List<ImportUse> usedImports() { return used; }

    /** 未被引用的导入（编译优化砍掉，非错误）。 */
    public List<StorageTable.ImportEntry> unusedImports() { return unused; }

    /** 最小登记集合：实际需从导入模块登记进存储表的符号（module::member / 裸符号名）。 */
    public Set<String> minimalImportSet() {
        Set<String> out = new LinkedHashSet<>();
        for (ImportUse u : used) out.addAll(u.refs());
        return out;
    }
}
