package common.sdscalp;

import com.optionscity.freeway.api.messages.Signal;

/**
 * Created by evoit on 8/21/2017.
 */
public class BollingerBandSignal extends Signal {
    public double lowerBand;
    public double middleBand;
    public double upperBand;
    public double bandWidth;
    public double lowerBand1k;
    public double upperBand1k;

    public BollingerBandSignal(double lowerBand, double middleBand, double upperBand){
        this.lowerBand = lowerBand;
        this.middleBand = middleBand;
        this.upperBand = upperBand;
    }
    public BollingerBandSignal(double lowerBand, double middleBand, double upperBand, double bandWidth){
        this.lowerBand = lowerBand;
        this.middleBand = middleBand;
        this.upperBand = upperBand;
        this.bandWidth = bandWidth;
    }
    public BollingerBandSignal(double lowerBand, double middleBand, double upperBand, double bandWidth, double lowerBand1k, double upperBand1k){
        this.lowerBand = lowerBand;
        this.middleBand = middleBand;
        this.upperBand = upperBand;
        this.bandWidth = bandWidth;
        this.lowerBand1k = lowerBand1k;
        this.upperBand1k = upperBand1k;
    }

}
