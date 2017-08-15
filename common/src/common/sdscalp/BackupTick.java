package common.sdscalp;

import org.joda.time.DateTime;

import java.io.Serializable;

/**
 * Created by evoit on 8/14/2017.
 */
public class BackupTick implements Serializable {
    public DateTime now;
    public double open;
    public double high;
    public double low;
    public double close;
    public double volume;

    public BackupTick(DateTime now, double open, double high, double low, double close, double volume){
        this.now = now;
        this.open=open;
        this.high=high;
        this.low=low;
        this.close=close;
        this.volume=volume;
    }
}
