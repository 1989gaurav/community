package org.neo4j.kernel.impl.util;

import java.io.IOException;
import java.lang.reflect.Array;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.ReadableByteChannel;

public abstract class IoPrimitiveUtils
{
    public static String readLengthAndString( ReadableByteChannel channel,
            ByteBuffer buffer ) throws IOException
    {
        Integer length = readInt( channel, buffer );
        String result = length != null ? readString( channel, buffer, length ) : null;
        return result;
    }
    
    public static String readString( ReadableByteChannel channel, ByteBuffer buffer,
            int length ) throws IOException
    {
        char[] chars = new char[length];
        chars = readCharArray( channel, buffer, chars );
        return chars == null ? null : new String( chars );
    }

    private static char[] readCharArray( ReadableByteChannel channel,
            ByteBuffer buffer, char[] charArray ) throws IOException
    {
        buffer.clear();
        int charsLeft = charArray.length;
        int maxSize = buffer.capacity() / 2;
        int offset = 0; // offset in chars
        while ( charsLeft > 0 )
        {
            if ( charsLeft > maxSize )
            {
                buffer.limit( maxSize * 2 );
                charsLeft -= maxSize;
            }
            else
            {
                buffer.limit( charsLeft * 2 );
                charsLeft = 0;
            }
            if ( channel.read( buffer ) != buffer.limit() )
            {
                return null;
            }
            buffer.flip();
            int length = buffer.limit() / 2;
            buffer.asCharBuffer().get( charArray, offset, length );
            offset += length;
            buffer.clear();
        }
        return charArray;
    }

    private static boolean readAndFlip( ReadableByteChannel channel, ByteBuffer buffer, int bytes )
            throws IOException
    {
        buffer.clear();
        buffer.limit( bytes );
        int read = channel.read( buffer );
        if ( read < bytes )
        {
            return false;
        }
        buffer.flip();
        return true;
    }

    public static Integer readInt( ReadableByteChannel channel, ByteBuffer buffer ) throws IOException
    {
        return readAndFlip( channel, buffer, 4 ) ? buffer.getInt() : null;
    }

    public static Long readLong( ReadableByteChannel channel, ByteBuffer buffer ) throws IOException
    {
        return readAndFlip( channel, buffer, 8 ) ? buffer.getLong() : null;
    }
    
    public static Float readFloat( ReadableByteChannel channel, ByteBuffer buffer ) throws IOException
    {
        return readAndFlip( channel, buffer, 4 ) ? buffer.getFloat() : null;
    }
    
    public static Double readDouble( ReadableByteChannel channel, ByteBuffer buffer ) throws IOException
    {
        return readAndFlip( channel, buffer, 8 ) ? buffer.getDouble() : null;
    }
    
    public static byte[] readBytes( ReadableByteChannel channel, byte[] array ) throws IOException
    {
        return readBytes( channel, array, array.length );
    }
    
    public static byte[] readBytes( ReadableByteChannel channel, byte[] array, int bytes ) throws IOException
    {
        return readAndFlip( channel, ByteBuffer.wrap( array ), bytes ) ? array : null;
    }
    
    public static void writeLengthAndString( FileChannel channel, ByteBuffer buffer, String value )
            throws IOException
    {
        char[] chars = value.toCharArray();
        int length = chars.length;
        writeInt( channel, buffer, length );
        writeChars( channel, buffer, chars );
    }
    
    private static void writeChars( FileChannel channel, ByteBuffer buffer, char[] chars )
            throws IOException
    {
        int position = 0;
        do
        {
            buffer.clear();
            int leftToWrite = chars.length - position;
            if ( leftToWrite * 2 < buffer.capacity() )
            {
                buffer.asCharBuffer().put( chars, position, leftToWrite );
                buffer.limit( leftToWrite * 2);
                channel.write( buffer );
                position += leftToWrite;
            }
            else
            {
                int length = buffer.capacity() / 2;
                buffer.asCharBuffer().put( chars, position, length );
                buffer.limit( length * 2 );
                channel.write( buffer );
                position += length;
            }
        } while ( position < chars.length );
    }
    
    public static void writeInt( FileChannel channel, ByteBuffer buffer, int value )
            throws IOException
    {
        buffer.clear();
        buffer.putInt( value );
        buffer.flip();
        channel.write( buffer );
    }

    public static Object[] asArray( Object propertyValue )
    {
        if ( propertyValue.getClass().isArray() )
        {
            int length = Array.getLength( propertyValue );
            Object[] result = new Object[ length ];
            for ( int i = 0; i < length; i++ )
            {
                result[ i ] = Array.get( propertyValue, i );
            }
            return result;
        }
        else
        {
            return new Object[] { propertyValue };
        }
    }
}
