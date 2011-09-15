/**
 * Copyright (c) 2002-2011 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.kernel.impl.storemigration;

import org.neo4j.helpers.UTF8;
import org.neo4j.kernel.impl.nioneo.store.*;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static org.neo4j.kernel.impl.storemigration.LegacyStore.*;

public class LegacyPropertyStoreReader
{
    public Iterable<LegacyPropertyRecord> readPropertyStore( String fileName ) throws IOException
    {
        FileChannel fileChannel = new RandomAccessFile( fileName, "r" ).getChannel();
        int recordLength = 25;
        int endHeaderSize = UTF8.encode( FROM_VERSION ).length;
        long recordCount = (fileChannel.size() - endHeaderSize) / recordLength;

        ByteBuffer buffer = ByteBuffer.allocateDirect( recordLength );

        ArrayList<LegacyPropertyRecord> records = new ArrayList<LegacyPropertyRecord>();
        for ( long id = 0; id < recordCount; id++ )
        {
            buffer.position( 0 );
            fileChannel.read( buffer );
            buffer.flip();
            // [    ,   x] in use
            // [xxxx,    ] high prev prop bits
            long inUseByte = buffer.get();

            boolean inUse = (inUseByte & 0x1) == Record.IN_USE.intValue();
            if ( inUse )
            {
                LegacyPropertyRecord record = new LegacyPropertyRecord( id );

                // [    ,    ][    ,    ][xxxx,xxxx][xxxx,xxxx] type
                // [    ,    ][    ,xxxx][    ,    ][    ,    ] high next prop bits
                long typeInt = buffer.getInt();

                record.setType( getEnumType( (int) typeInt & 0xFFFF ) );
                record.setInUse( true );
                record.setKeyIndexId( buffer.getInt() );
                record.setPropBlock( buffer.getLong() );

                long prevProp = getUnsignedInt( buffer );
                long prevModifier = (inUseByte & 0xF0L) << 28;
                long nextProp = getUnsignedInt( buffer );
                long nextModifier = (typeInt & 0xF0000L) << 16;

                record.setPrevProp( longFromIntAndMod( prevProp, prevModifier ) );
                record.setNextProp( longFromIntAndMod( nextProp, nextModifier ) );

                records.add( record );
            }
        }
        return records;
    }

    private PropertyType getEnumType( int type )
    {
        return PropertyType.getPropertyType( type, false );
    }

    public static class LegacyPropertyRecord extends Abstract64BitRecord
    {
        private PropertyType type;
        private int keyIndexId = Record.NO_NEXT_BLOCK.intValue();
        private long propBlock = Record.NO_NEXT_BLOCK.intValue();
        private long prevProp = Record.NO_PREVIOUS_PROPERTY.intValue();
        private long nextProp = Record.NO_NEXT_PROPERTY.intValue();
        private List<DynamicRecord> valueRecords = new ArrayList<DynamicRecord>();
        private boolean isLight = false;
        private long nodeRelId = -1;
        private boolean nodeIdSet = false;
        private boolean isChanged = false;

        public LegacyPropertyRecord( long id )
        {
            super( id );
        }

        public void setType( PropertyType type )
        {
            this.type = type;
        }

        public void setNodeId( long nodeId )
        {
            nodeIdSet = true;
            nodeRelId = nodeId;
        }

        public void setRelId( long relId )
        {
            nodeIdSet = false;
            nodeRelId = relId;
        }

        public long getNodeId()
        {
            if ( nodeIdSet )
            {
                return nodeRelId;
            }
            return -1;
        }

        public long getRelId()
        {
            if ( !nodeIdSet )
            {
                return nodeRelId;
            }
            return -1;
        }

        void setIsLight( boolean status )
        {
            isLight = status;
        }

        public boolean isLight()
        {
            return isLight;
        }

        public Collection<DynamicRecord> getValueRecords()
        {
            assert !isLight;
            return valueRecords;
        }

        public void addValueRecord( DynamicRecord record )
        {
            assert !isLight;
            valueRecords.add( record );
        }

        public PropertyType getType()
        {
            return type;
        }

        public int getKeyIndexId()
        {
            return keyIndexId;
        }

        public void setKeyIndexId( int keyId )
        {
            this.keyIndexId = keyId;
        }

        public long getPropBlock()
        {
            return propBlock;
        }

        public void setPropBlock( long propBlock )
        {
            this.propBlock = propBlock;
        }

        public long getPrevProp()
        {
            return prevProp;
        }

        public void setPrevProp( long prevProp )
        {
            this.prevProp = prevProp;
        }

        public long getNextProp()
        {
            return nextProp;
        }

        public void setNextProp( long nextProp )
        {
            this.nextProp = nextProp;
        }

//        public PropertyData newPropertyData()
//        {
//            return getType().newPropertyData( this, null );
//        }
//
//        public PropertyData newPropertyData( Object extractedValue )
//        {
//            return getType().newPropertyData( this, extractedValue );
//        }

        @Override
        public String toString()
        {
            StringBuffer buf = new StringBuffer();
            buf.append( "LegacyPropertyRecord[" ).append( getId() ).append( "," ).append(
                    inUse() ).append( "," ).append( type ).append( "," ).append(
                    keyIndexId ).append( "," ).append( propBlock ).append( "," )
                    .append( prevProp ).append( "," ).append( nextProp );
            buf.append( ", Value[" );
            for ( DynamicRecord record : valueRecords )
            {
                buf.append( record );
            }
            buf.append( "]]" );
            return buf.toString();
        }

        public boolean isChanged()
        {
            return isChanged;
        }

        public void setChanged()
        {
            isChanged = true;
        }

    }

    public enum PropertyType
    {
        ILLEGAL( 0 )
                {
                    @Override
                    public Object getValue( LegacyPropertyRecord record, PropertyStore store )
                    {
                        throw new InvalidRecordException( "Invalid type: 0 for record " + record );
                    }

                    @Override
                    public PropertyData newPropertyData( LegacyPropertyRecord record, Object extractedValue )
                    {
                        throw new InvalidRecordException( "Invalid type: 0 for record " + record );
                    }
                },
        INT( 1 )
                {
                    @Override
                    public Object getValue( LegacyPropertyRecord record, PropertyStore store )
                    {
                        return Integer.valueOf( (int) record.getPropBlock() );
                    }

                    @Override
                    public PropertyData newPropertyData( LegacyPropertyRecord record, Object extractedValue )
                    {
                        return PropertyDatas.forInt( record.getKeyIndexId(), record.getId(), (int) record.getPropBlock() );
                    }
                },
        STRING( 2 )
                {
                    @Override
                    public Object getValue( LegacyPropertyRecord record, PropertyStore store )
                    {
//                        if ( store == null )
//                        {
                            return null;
//                        }
//                        return store.getStringFor( record );
                    }

                    @Override
                    public PropertyData newPropertyData( LegacyPropertyRecord record, Object extractedValue )
                    {
                        return PropertyDatas.forStringOrArray( record.getKeyIndexId(), record.getId(), extractedValue );
                    }
                },
        BOOL( 3 )
                {
                    @Override
                    public Object getValue( LegacyPropertyRecord record, PropertyStore store )
                    {
                        return getValue( record.getPropBlock() );
                    }

                    private Boolean getValue( long propBlock )
                    {
                        return propBlock == 1 ? Boolean.TRUE : Boolean.FALSE;
                    }

                    @Override
                    public PropertyData newPropertyData( LegacyPropertyRecord record, Object extractedValue )
                    {
                        return PropertyDatas.forBoolean( record.getKeyIndexId(), record.getId(),
                                getValue( record.getPropBlock() ).booleanValue() );
                    }
                },
        DOUBLE( 4 )
                {
                    @Override
                    public Object getValue( LegacyPropertyRecord record, PropertyStore store )
                    {
                        return Double.valueOf( Double.longBitsToDouble( record.getPropBlock() ) );
                    }

                    private double getValue( long propBlock )
                    {
                        return Double.longBitsToDouble( propBlock );
                    }

                    @Override
                    public PropertyData newPropertyData( LegacyPropertyRecord record, Object extractedValue )
                    {
                        return PropertyDatas.forDouble( record.getKeyIndexId(), record.getId(), getValue( record.getPropBlock() ) );
                    }
                },
        FLOAT( 5 )
                {
                    @Override
                    public Object getValue( LegacyPropertyRecord record, PropertyStore store )
                    {
                        return Float.valueOf( getValue( record.getPropBlock() ) );
                    }

                    private float getValue( long propBlock )
                    {
                        return Float.intBitsToFloat( (int) propBlock );
                    }

                    @Override
                    public PropertyData newPropertyData( LegacyPropertyRecord record, Object extractedValue )
                    {
                        return PropertyDatas.forFloat( record.getKeyIndexId(), record.getId(), getValue( record.getPropBlock() ) );
                    }
                },
        LONG( 6 )
                {
                    @Override
                    public Object getValue( LegacyPropertyRecord record, PropertyStore store )
                    {
                        return Long.valueOf( record.getPropBlock() );
                    }

                    @Override
                    public PropertyData newPropertyData( LegacyPropertyRecord record, Object extractedValue )
                    {
                        return PropertyDatas.forLong( record.getKeyIndexId(), record.getId(), record.getPropBlock() );
                    }
                },
        BYTE( 7 )
                {
                    @Override
                    public Object getValue( LegacyPropertyRecord record, PropertyStore store )
                    {
                        return Byte.valueOf( (byte) record.getPropBlock() );
                    }

                    @Override
                    public PropertyData newPropertyData( LegacyPropertyRecord record, Object extractedValue )
                    {
                        return PropertyDatas.forByte( record.getKeyIndexId(), record.getId(), (byte) record.getPropBlock() );
                    }
                },
        CHAR( 8 )
                {
                    @Override
                    public Object getValue( LegacyPropertyRecord record, PropertyStore store )
                    {
                        return Character.valueOf( (char) record.getPropBlock() );
                    }

                    @Override
                    public PropertyData newPropertyData( LegacyPropertyRecord record, Object extractedValue )
                    {
                        return PropertyDatas.forChar( record.getKeyIndexId(), record.getId(), (char) record.getPropBlock() );
                    }
                },
        ARRAY( 9 )
                {
                    @Override
                    public Object getValue( LegacyPropertyRecord record, PropertyStore store )
                    {
//                        if ( store == null )
//                        {
                            return null;
//                        }
//                        return store.getArrayFor( record );
                    }

                    @Override
                    public PropertyData newPropertyData( LegacyPropertyRecord record, Object extractedValue )
                    {
                        return PropertyDatas.forStringOrArray( record.getKeyIndexId(), record.getId(), extractedValue );
                    }
                },
        SHORT( 10 )
                {
                    @Override
                    public Object getValue( LegacyPropertyRecord record, PropertyStore store )
                    {
                        return Short.valueOf( (short) record.getPropBlock() );
                    }

                    @Override
                    public PropertyData newPropertyData( LegacyPropertyRecord record, Object extractedValue )
                    {
                        return PropertyDatas.forShort( record.getKeyIndexId(), record.getId(), (short) record.getPropBlock() );
                    }
                },
        SHORT_STRING( 11 )
                {
                    @Override
                    public Object getValue( LegacyPropertyRecord record, PropertyStore store )
                    {
                        return ShortString.decode( record.getPropBlock() );
                    }

                    @Override
                    public PropertyData newPropertyData( LegacyPropertyRecord record, Object extractedValue )
                    {
                        return PropertyDatas.forStringOrArray( record.getKeyIndexId(), record.getId(), getValue( record, null ) );
                    }
                };

        private int type;

        PropertyType( int type )
        {
            this.type = type;
        }

        /**
         * Returns an int value representing the type.
         *
         * @return The int value for this property type
         */
        public int intValue()
        {
            return type;
        }

        public abstract Object getValue( LegacyPropertyRecord record, PropertyStore store );

        public abstract PropertyData newPropertyData( LegacyPropertyRecord record, Object extractedValue );

        public static PropertyType getPropertyType( int type, boolean nullOnIllegal )
        {
            switch ( type )
            {
                case 0:
                    if ( nullOnIllegal )
                    {
                        return null;
                    }
                    break;
                case 1:
                    return INT;
                case 2:
                    return STRING;
                case 3:
                    return BOOL;
                case 4:
                    return DOUBLE;
                case 5:
                    return FLOAT;
                case 6:
                    return LONG;
                case 7:
                    return BYTE;
                case 8:
                    return CHAR;
                case 9:
                    return ARRAY;
                case 10:
                    return SHORT;
                case 11:
                    return SHORT_STRING;
            }
            throw new InvalidRecordException( "Unknown property type:" + type );
        }
    }

}
