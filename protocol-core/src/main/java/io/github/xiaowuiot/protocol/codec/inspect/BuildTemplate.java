package io.github.xiaowuiot.protocol.codec.inspect;

import java.util.ArrayList;
import java.util.List;

/**
 * 报文构造模板：一条消息要填哪些字段。页面按它生成表单，填完交给 {@link ProtocolInspector#build} 生成报文。
 */
public class BuildTemplate {

    private final String messageId;
    private final String name;
    private final String direction;
    private final List<Input> inputs = new ArrayList<>();

    public BuildTemplate(String messageId, String name, String direction) {
        this.messageId = messageId;
        this.name = name;
        this.direction = direction;
    }

    public BuildTemplate input(String key, String label, String type, String defaultValue, String hint) {
        inputs.add(new Input(key, label, type, defaultValue, hint));
        return this;
    }

    public String getMessageId() {
        return messageId;
    }

    public String getName() {
        return name;
    }

    public String getDirection() {
        return direction;
    }

    public List<Input> getInputs() {
        return inputs;
    }

    /**
     * 一个输入项
     *
     * @param type text 文本、number 数字、time 时间（yyyy-MM-dd HH:mm:ss）、select:A|B|C 下拉、hex 十六进制
     */
    public static class Input {
        private final String key;
        private final String label;
        private final String type;
        private final String defaultValue;
        private final String hint;

        Input(String key, String label, String type, String defaultValue, String hint) {
            this.key = key;
            this.label = label;
            this.type = type;
            this.defaultValue = defaultValue;
            this.hint = hint;
        }

        public String getKey() {
            return key;
        }

        public String getLabel() {
            return label;
        }

        public String getType() {
            return type;
        }

        public String getDefaultValue() {
            return defaultValue;
        }

        public String getHint() {
            return hint;
        }
    }
}
