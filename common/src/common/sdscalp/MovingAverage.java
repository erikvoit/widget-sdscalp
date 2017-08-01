package common.sdscalp;

import static com.sun.org.apache.xalan.internal.lib.ExsltMath.power;
import static java.lang.Math.pow;

/**
 * Created by evoit on 7/31/2017.
 */
public class MovingAverage {

    // EMA uses double[] of prices with newest price in index 0;
    public static double ema(int numberOfDays, double[] prices, int startindex) {
        double numerator = 0;
        double denominator = 0;

        double exponent = 2.0 / (numberOfDays + 1);

        double exp = 1.0 - exponent;
        double average = 0.0;

        for (int i = 0; i <= startindex; i++)
        {
            average += prices[i];
        }
        average = average / (startindex + 1);

        //exponential moving average
        double beta = .9;
        int i = 0;
        for (i = startindex + numberOfDays - 1; i > startindex; i--)
        {
            numerator += prices[i] * pow(beta, startindex + numberOfDays - i - 1);
            denominator += pow(beta, startindex + numberOfDays - i - 1);
        }

        numerator += average * pow(beta, startindex + numberOfDays);
        denominator += pow(beta, startindex + numberOfDays);
        return numerator / denominator;
    }
}
