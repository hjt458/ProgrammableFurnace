package dev.programmablefurnace.base;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 全模组共用的日志出口。
 *
 * <p>以前各处直接 {@code import dev.programmablefurnace.ProgrammableFurnaceMod} 只为拿一个
 * {@code LOGGER}，那是"下层去认识入口类"的反向依赖里最没道理的一种：日志跟入口类毫无关系。
 * 现在所有包都从这里取，入口类只剩组装职责。</p>
 */
public final class ModLog {

    private ModLog() {}

    /** 模组级 logger，名字就是 mod id，便于按包过滤。 */
    public static final Logger LOG = LoggerFactory.getLogger(ModInfo.MODID);
}
