/**
 * Autogenerated by Thrift Compiler (0.13.0)
 *
 * DO NOT EDIT UNLESS YOU ARE SURE THAT YOU KNOW WHAT YOU ARE DOING
 *  @generated
 */
package cn.edu.tsinghua.iginx.thrift;


@javax.annotation.Generated(value = "Autogenerated by Thrift Compiler (0.13.0)", date = "2021-03-18")
public enum AggregateType implements org.apache.thrift.TEnum {
  MAX(0),
  MIN(1),
  SUM(2),
  COUNT(3),
  AVG(4),
  FIRST(5),
  LAST(6);

  private final int value;

  private AggregateType(int value) {
    this.value = value;
  }

  /**
   * Get the integer value of this enum value, as defined in the Thrift IDL.
   */
  public int getValue() {
    return value;
  }

  /**
   * Find a the enum type by its integer value, as defined in the Thrift IDL.
   * @return null if the value is not found.
   */
  @org.apache.thrift.annotation.Nullable
  public static AggregateType findByValue(int value) { 
    switch (value) {
      case 0:
        return MAX;
      case 1:
        return MIN;
      case 2:
        return SUM;
      case 3:
        return COUNT;
      case 4:
        return AVG;
      case 5:
        return FIRST;
      case 6:
        return LAST;
      default:
        return null;
    }
  }
}