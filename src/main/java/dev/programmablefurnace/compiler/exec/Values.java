package dev.programmablefurnace.compiler.exec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 脚本语言里的复合值：{@code pair}、{@code tuple}、{@code map}。
 *
 * <p>草稿 §3.1.1 只把这些类型名写进了语法，运行期从来没有值——`pair&lt;int,string&gt; p;`
 * 能编译，但 `p.first` 拿不到东西。这里补齐最小可用的运行期表示：
 *
 * <ul>
 *   <li>{@link Pair}：两个具名字段 {@code first} / {@code second}，可读可写；</li>
 *   <li>{@link Tuple}：按整数下标存取，长度固定；</li>
 *   <li>{@link MapValue}：按任意键存取（插入顺序稳定，便于遍历与调试），
 *       {@code size()} / {@code has(k)} / {@code keys()}。</li>
 * </ul>
 *
 * <p>都是纯 JVM 对象，不认识 Minecraft——和 {@code Executor} 的其它值一样，
 * 只在解释器内部流通；要暴露给游戏侧的对象仍然走 {@code Env}。
 */
public final class Values {

    private Values() {
    }

    /** {@code pair<A,B>} 的运行期值：两个可读写字段。 */
    public static final class Pair {
        private Object first;
        private Object second;

        public Pair(Object first, Object second) {
            this.first = first;
            this.second = second;
        }

        public Object first() {
            return first;
        }

        public Object second() {
            return second;
        }

        public void setFirst(Object value) {
            this.first = value;
        }

        public void setSecond(Object value) {
            this.second = value;
        }

        @Override
        public String toString() {
            return "(" + first + ", " + second + ")";
        }
    }

    /** {@code tuple<...>} 的运行期值：定长、按整数下标存取。 */
    public static final class Tuple {
        private final List<Object> items;

        public Tuple(List<Object> items) {
            this.items = new ArrayList<>(items);
        }

        public List<Object> items() {
            return items;
        }

        public int size() {
            return items.size();
        }

        @Override
        public String toString() {
            return "(" + String.join(", ", items.stream().map(String::valueOf).toList()) + ")";
        }
    }

    /** {@code map<K,V>} 的运行期值：插入顺序稳定。 */
    public static final class MapValue {
        private final Map<Object, Object> entries = new LinkedHashMap<>();

        public int size() {
            return entries.size();
        }

        public boolean has(Object key) {
            return entries.containsKey(key);
        }

        public Object get(Object key) {
            return entries.get(key);
        }

        public void put(Object key, Object value) {
            entries.put(key, value);
        }

        public List<Object> keys() {
            return new ArrayList<>(entries.keySet());
        }

        public Map<Object, Object> entries() {
            return entries;
        }

        @Override
        public String toString() {
            return entries.toString();
        }
    }
}
