package com.claro.intermediate_representation.expressions.numeric;

import com.claro.ClaroParserException;
import com.claro.compiler_backends.interpreted.ScopedHeap;
import com.claro.intermediate_representation.expressions.Expr;
import com.claro.intermediate_representation.types.ClaroTypeException;
import com.claro.intermediate_representation.types.Type;
import com.claro.intermediate_representation.types.Types;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

public class MultiplyNumericExpr extends NumericExpr {
  // TODO(steving) In the future, assume that operator* is able to be used on arbitrary Comparable impls. So check
  // TODO(steving) the type of each in the heap and see if they are implementing Comparable, and call their impl of
  // TODO(steving) Operators::multiply.
  private static final ImmutableSet<Type> SUPPORTED_MULTIPLY_OPERAND_TYPES =
      ImmutableSet.of(Types.INTEGER, Types.FLOAT);
  private static final String TYPE_SUPPORT_ERROR_MESSAGE =
      "Internal Compiler Error: Currently `*` is not supported for types other than Integer and Double.";

  // TODO(steving) This should only accept other NumericExpr args. Need to update the grammar.
  public MultiplyNumericExpr(Expr lhs, Expr rhs) {
    super(ImmutableList.of(lhs, rhs));
  }

  @Override
  public Type getValidatedExprType(ScopedHeap scopedHeap) throws ClaroTypeException {
    Type lhs = ((Expr) this.getChildren().get(0)).getValidatedExprType(scopedHeap);
    Type rhs = ((Expr) this.getChildren().get(1)).getValidatedExprType(scopedHeap);

    // Make sure we're at least looking at supported types.
    assertSupportedExprType(lhs, SUPPORTED_MULTIPLY_OPERAND_TYPES);
    assertSupportedExprType(rhs, SUPPORTED_MULTIPLY_OPERAND_TYPES);

    if (lhs.equals(Types.FLOAT) || rhs.equals(Types.FLOAT)) {
      return Types.FLOAT;
    } else {
      return Types.INTEGER;
    }
  }

  @Override
  public StringBuilder generateJavaSourceBodyOutput(ScopedHeap scopedHeap) {
    return new StringBuilder(
        String.format(
            "(%s * %s)",
            ((Expr) this.getChildren().get(0)).generateJavaSourceBodyOutput(scopedHeap),
            ((Expr) this.getChildren().get(1)).generateJavaSourceBodyOutput(scopedHeap)
        )
    );
  }

  // TODO(steving) This might be the point where switching the compiler implementation to ~Kotlin~ will be a legitimate
  // TODO(steving) win. I believe that Kotlin supports multiple-dispatch which I think would allow this entire garbage
  // TODO(steving) mess of instanceof checks to be reduced to a single function call passing the lhs and rhs, and that
  // TODO(steving) function would have a few different impls taking args of different types and the correct one would be
  // TODO(steving) called. I guess in that case it's just the runtime itself handling these instanceof checks.
  @Override
  public Object generateInterpretedOutput(ScopedHeap scopedHeap) {
    Object lhs = this.getChildren().get(0).generateInterpretedOutput(scopedHeap);
    Object rhs = this.getChildren().get(1).generateInterpretedOutput(scopedHeap);
    if (lhs instanceof Double && rhs instanceof Double) {
      return (Double) lhs * (Double) rhs;
    } else if (lhs instanceof Integer && rhs instanceof Integer) {
      return (Integer) lhs * (Integer) rhs;
    } else if ((lhs instanceof Integer && rhs instanceof Double) || (lhs instanceof Double && rhs instanceof Integer)) {
      Double lhsDouble;
      Double rhsDouble;
      if (lhs instanceof Integer) {
        lhsDouble = ((Integer) lhs).doubleValue();
        rhsDouble = (Double) rhs;
      } else {
        lhsDouble = (Double) lhs;
        rhsDouble = ((Integer) rhs).doubleValue();
      }
      return lhsDouble * rhsDouble;
    } else {
      throw new ClaroParserException(TYPE_SUPPORT_ERROR_MESSAGE);
    }
  }
}