package dev.programmablefurnace.compiler.sem;

import java.util.HashMap;
import java.util.Map;

/**
 * 模块登记表（跨模块 import 解析 / 链接期的输入）：按模块名登记各模块的存储表。
 * <p>游戏内代码按模块独立（草稿 §2.4），跨文件引用只能经 {@code import} 显式引入。
 * 由链接器在具备全部模块时填充，供 {@link ImportResolver} 校验（M 类错误）。</p>
 */
public final class ModuleRegistry {

    private final Map<String, StorageTable> modules = new HashMap<>();

    /** 登记一个模块的存储表（通常由该模块源码 parse + StorageBuilder.build 得到）。 */
    public void register(String moduleName, StorageTable table) {
        modules.put(moduleName, table);
    }

    /** 按模块名取存储表；未登记返回 null。 */
    public StorageTable resolve(String moduleName) {
        return modules.get(moduleName);
    }

    public boolean contains(String moduleName) {
        return modules.containsKey(moduleName);
    }
}
