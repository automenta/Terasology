package org.terasology.world.chunks.blockdata;

import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;

import com.google.common.base.Preconditions;


/**
 * TeraDenseArrayByte is the base class used to implement dense arrays with elements of size 4 bit or 8 bit.
 * 
 * @author Manuel Brotz <manu.brotz@gmx.ch>
 *
 */
public abstract class TeraDenseArrayByte extends TeraDenseArray {

    protected byte[] data; 
    
    protected abstract TeraArray createSparse(byte fill);
    
    protected abstract TeraArray createSparse(byte[][] inflated, byte[] deflated);

    protected abstract TeraArray createDense(byte[] data);
    
    protected abstract int rowSize();
    
    protected final int dataSize() {
        return getSizeY() * rowSize();
    }

    public TeraDenseArrayByte() {
        super();
    }

    public TeraDenseArrayByte(int sizeX, int sizeY, int sizeZ) {
        super(sizeX, sizeY, sizeZ);
        this.data = new byte[dataSize()];
    }
    
    public TeraDenseArrayByte(int sizeX, int sizeY, int sizeZ, byte[] data) {
        super(sizeX, sizeY, sizeZ);
        this.data = Preconditions.checkNotNull(data);
        Preconditions.checkArgument(data.length == dataSize(), "The length of the parameter 'data' has to be " + dataSize() + " (" + data.length + ")");
    }
    
    public TeraDenseArrayByte(TeraArray in) {
        super(in);
    }

    @Override
    public final int getEstimatedMemoryConsumptionInBytes() {
        return 16 + dataSize();
    }

    @Override
    public final TeraArray copy() {
        byte[] result = new byte[dataSize()];
        System.arraycopy(data, 0, result, 0, dataSize());
        return createDense(result);
    }

    @Override
    public final void writeExternal(ObjectOutput out) throws IOException {
        writeExternalHeader(out);
        out.writeObject(data);
    }

    @Override
    public final void readExternal(ObjectInput in) throws IOException, ClassNotFoundException {
        readExternalHeader(in);
        data = (byte[]) in.readObject();
    }
}
