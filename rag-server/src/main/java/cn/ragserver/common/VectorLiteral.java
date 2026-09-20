package cn.ragserver.common;

/**
 * pgvector 字面量的构造。
 *
 * 写入和检索两处都要用,所以抽成公共工具,避免各写一份。
 */
public final class VectorLiteral {

    private VectorLiteral() {
    }

    /**
     * 把 float 数组转成 pgvector 认得的格式:[0.1,0.2,0.3]
     *
     * 这里手工拼字符串而不是用 JSON 序列化:pgvector 的输入格式看起来像 JSON 数组,
     * 但它不是 JSON,用 JSON 库反而可能引入多余的引号。
     */
    public static String of(float[] vector) {
        StringBuilder sb = new StringBuilder(vector.length * 8).append('[');
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(vector[i]);
        }
        return sb.append(']').toString();
    }
}
