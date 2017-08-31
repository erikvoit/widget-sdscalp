package common.sdscalp;

import com.optionscity.freeway.api.Order.TimeInForce;

/**
 * Created by evoit on 8/17/2017.
 */
public class TrackedOrder
{
    public final long orderId;
    public final String instId;
    public final TimeInForce timeInForce;
    /**
     * 0=Undefined, 1=PendingBooked, 2=PendingCancel, 3=Booked, 4=Filled, 5=Cancelled
     */
    public int state = 0;
    /**
     * Original order qty. Positive values represent buy qty; negative values represent sell qty.
     */
    public int signedOriginalQty;
    /**
     * The order's current "remaining" qty. Positive values represent buy qty; negative values represent sell qty.
     */
    public int signedQty;
    /**
     * Original order price.
     */
    public final double originalPrice;

    /**
     * Current order price.
     */
    public double price;

    /**
     *
     * @param signedQty Positive values represent buy qty; negative values represent sell qty.
     */

    public TrackedOrder(long orderId, String instId, TimeInForce timeInForce, int signedQty,
                        double origPrice)
    {
        this.orderId = orderId;
        this.instId = instId;
        this.timeInForce = timeInForce;
        this.state = 1;
        this.signedOriginalQty = signedQty;
        this.signedQty = signedQty;
        this.originalPrice = origPrice;
        this.price = origPrice;
    }

    public boolean IsActive()
    {
        return (this.state == 3 || this.state == 1);
    }

    public boolean isLive()
    {
        return (this.state == 0 || this.state == 1 || this.state == 2 || this.state == 3);
    }
}
