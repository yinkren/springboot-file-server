package com.ykrenz.file.exception;

import com.ykrenz.file.model.BizErrorMessage;
import lombok.Getter;

/**
 * @author Mr Ren
 * @Description: api异常
 * @date 2021/4/7 10:57
 */
@Getter
public class BizException extends RuntimeException {

    private final BizErrorMessage errorMessage;

    private boolean reset;

    public BizException(BizErrorMessage message) {
        super(message.getMessage());
        this.errorMessage = message;
    }

    public BizException(BizErrorMessage message, boolean reset) {
        super(message.getMessage());
        this.errorMessage = message;
        this.reset = reset;
    }
}
