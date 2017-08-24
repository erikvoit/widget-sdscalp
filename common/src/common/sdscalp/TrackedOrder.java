package common.sdscalp;

import com.optionscity.freeway.api.Order;
import com.optionscity.freeway.api.Order.HedgeMode;
import com.optionscity.freeway.api.Order.Side;
import com.optionscity.freeway.api.Order.TimeInForce;
import com.optionscity.freeway.api.OrderRequest;

/**
 * Created by evoit on 8/17/2017.
 */
public class TrackedOrder
{
    public final long OrderId;
    public final String InstId;
    public final TimeInForce TimeInForce;
    /**
     * 0=Undefined, 1=PendingBooked, 2=PendingCancel, 3=Booked, 4=Filled, 5=Cancelled
     */
    public int State = 0;
    /**
     * Original order qty. Positive values represent buy qty; negative values represent sell qty.
     */
    public int SignedOriginalQty;
    /**
     * The order's current "remaining" qty. Positive values represent buy qty; negative values represent sell qty.
     */
    public int SignedQty;
    /**
     * Original order price.
     */
    public final double OriginalPrice;

    /**
     * Current order price.
     */
    public double Price;

    /**
     *
     * @param signedQty Positive values represent buy qty; negative values represent sell qty.
     */
    public TrackedOrder(long orderId, String instId, TimeInForce timeInForce, int signedQty,
                        double origPrice)
    {
        this.OrderId = orderId;
        this.InstId = instId;
        this.TimeInForce = timeInForce;
        this.State = 1;
        this.SignedOriginalQty = signedQty;
        this.SignedQty = signedQty;
        this.OriginalPrice = origPrice;
        this.Price = origPrice;
    }

    public boolean IsActive()
    {
        return (this.State == 3 || this.State == 1);
    }

    public boolean isLive()
    {
        return (this.State == 0 || this.State == 1 || this.State == 2 || this.State == 3);
    }
}
