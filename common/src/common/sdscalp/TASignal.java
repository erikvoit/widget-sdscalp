package common.sdscalp;

import com.optionscity.freeway.api.messages.Signal;

/**
 * Created by evoit on 8/2/2017.
 */
public class TASignal extends Signal {
    public final String message;

    public TASignal(String message) {
        this.message = message;
    }
}
