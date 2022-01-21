package com.claro.intermediate_representation.types.impls.builtins_impls.procedures;

import com.claro.intermediate_representation.types.Type;
import com.claro.intermediate_representation.types.impls.builtins_impls.ClaroBuiltinTypeImplementation;

public abstract class ClaroProviderFunction<T> implements ClaroBuiltinTypeImplementation {

  public abstract T apply();

  @Override
  public abstract Type getClaroType();
}
