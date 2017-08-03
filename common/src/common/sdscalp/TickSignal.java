package common.sdscalp;

import com.optionscity.freeway.api.messages.Signal;
import eu.verdelhan.ta4j.Tick;

/**
 * Created by evoit on 8/3/2017.
 */
public class TickSignal extends Signal {
    public final Tick tick;

    public TickSignal(Tick tick) {
        this.tick = tick;
    }
}
