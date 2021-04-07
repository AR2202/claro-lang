package com.claro.examples.calculator_example.intermediate_representation;

import com.claro.examples.calculator_example.CalculatorParserException;
import com.claro.examples.calculator_example.compiler_backends.interpreted.ScopedHeap;
import com.claro.examples.calculator_example.intermediate_representation.types.Type;

public class IdentifierReferenceTerm extends Term {

  private final String identifier;

  public IdentifierReferenceTerm(String identifier) {
    // Hold onto the relevant data for code-gen later.
    this.identifier = identifier;
  }

  public String getIdentifier() {
    return identifier;
  }

  @Override
  protected Type getValidatedExprType(ScopedHeap scopedHeap) {
    return scopedHeap.getValidatedIdentifierType(this.identifier);
  }

  @Override
  protected StringBuilder generateJavaSourceOutput(ScopedHeap scopedHeap) {
    // Make sure we check this will actually be a valid reference before we allow it.
    if (scopedHeap.isIdentifierDeclared(identifier)) {
      scopedHeap.markIdentifierUsed(identifier);
      return new StringBuilder(this.identifier);
    }
    throw new CalculatorParserException(String.format("No variable <%s> within the current scope!", identifier));
  }

  @Override
  protected Object generateInterpretedOutput(ScopedHeap scopedHeap) {
    return scopedHeap.getIdentifierValue(this.identifier);
  }
}
