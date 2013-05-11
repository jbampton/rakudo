package org.perl6.rakudo;

import java.util.*;

import org.perl6.nqp.runtime.*;
import org.perl6.nqp.sixmodel.*;

public final class Binder {
    /* Possible results of binding. */
    public static final int BIND_RESULT_OK       = 0;
    public static final int BIND_RESULT_FAIL     = 1;
    public static final int BIND_RESULT_JUNCTION = 2;
    
    /* Compile time trial binding result indicators. */
    public static final int TRIAL_BIND_NOT_SURE =  0;  /* Plausible, but need to check at runtime. */
    public static final int TRIAL_BIND_OK       =  1;  /* Bind will always work out. */
    public static final int TRIAL_BIND_NO_WAY   = -1;  /* Bind could never work out. */
    
    /* Flags. */
    private static final int SIG_ELEM_BIND_CAPTURE        = 1;
    private static final int SIG_ELEM_BIND_PRIVATE_ATTR   = 2;
    private static final int SIG_ELEM_BIND_PUBLIC_ATTR    = 4;
    private static final int SIG_ELEM_BIND_ATTRIBUTIVE    = (SIG_ELEM_BIND_PRIVATE_ATTR | SIG_ELEM_BIND_PUBLIC_ATTR);
    private static final int SIG_ELEM_SLURPY_POS          = 8;
    private static final int SIG_ELEM_SLURPY_NAMED        = 16;
    private static final int SIG_ELEM_SLURPY_LOL          = 32;
    private static final int SIG_ELEM_SLURPY              = (SIG_ELEM_SLURPY_POS | SIG_ELEM_SLURPY_NAMED | SIG_ELEM_SLURPY_LOL);
    private static final int SIG_ELEM_INVOCANT            = 64;
    private static final int SIG_ELEM_MULTI_INVOCANT      = 128;
    private static final int SIG_ELEM_IS_RW               = 256;
    private static final int SIG_ELEM_IS_COPY             = 512;
    private static final int SIG_ELEM_IS_PARCEL           = 1024;
    private static final int SIG_ELEM_IS_OPTIONAL         = 2048;
    private static final int SIG_ELEM_ARRAY_SIGIL         = 4096;
    private static final int SIG_ELEM_HASH_SIGIL          = 8192;
    private static final int SIG_ELEM_DEFAULT_FROM_OUTER  = 16384;
    private static final int SIG_ELEM_IS_CAPTURE          = 32768;
    private static final int SIG_ELEM_UNDEFINED_ONLY      = 65536;
    private static final int SIG_ELEM_DEFINED_ONLY        = 131072;
    private static final int SIG_ELEM_DEFINEDNES_CHECK    = (SIG_ELEM_UNDEFINED_ONLY | SIG_ELEM_DEFINED_ONLY);
    private static final int SIG_ELEM_NOMINAL_GENERIC     = 524288;
    private static final int SIG_ELEM_DEFAULT_IS_LITERAL  = 1048576;
    private static final int SIG_ELEM_NATIVE_INT_VALUE    = 2097152;
    private static final int SIG_ELEM_NATIVE_NUM_VALUE    = 4194304;
    private static final int SIG_ELEM_NATIVE_STR_VALUE    = 8388608;
    private static final int SIG_ELEM_NATIVE_VALUE        = (SIG_ELEM_NATIVE_INT_VALUE | SIG_ELEM_NATIVE_NUM_VALUE | SIG_ELEM_NATIVE_STR_VALUE);
    
    /* Hints for Parameter attributes. */
    private static final int HINT_variable_name = 0;
    private static final int HINT_named_names = 1;
    private static final int HINT_type_captures = 2;
    private static final int HINT_flags = 3;
    private static final int HINT_nominal_type = 4;
    private static final int HINT_post_constraints = 5;
    private static final int HINT_coerce_type = 6;
    private static final int HINT_coerce_method = 7;
    private static final int HINT_sub_signature = 8;
    private static final int HINT_default_value = 9;
    private static final int HINT_container_descriptor = 10;
    private static final int HINT_attr_package = 11;
    
    /* Last error, per thread. */
    public static HashMap<ThreadContext, String> lastErrors = new HashMap<ThreadContext, String>();
    
    private static SixModelObject createBox(ThreadContext tc, Object arg, int flag) {
        switch (flag) {
            case CallSiteDescriptor.ARG_INT:
                return org.perl6.nqp.runtime.Ops.box_i((long)arg, Ops.Int, tc);
            case CallSiteDescriptor.ARG_NUM:
                return org.perl6.nqp.runtime.Ops.box_n((double)arg, Ops.Num, tc);
            case CallSiteDescriptor.ARG_STR:
                return org.perl6.nqp.runtime.Ops.box_s((String)arg, Ops.Str, tc);
            default:
                throw new RuntimeException("Impossible case reached in createBox");
        }
    }
    
    private static String arityFail(ThreadContext tc, SixModelObject params,
            int numParams, int numPosArgs, boolean tooMany) {
        int arity = 0;
        int count = 0;
        String fail = tooMany ? "Too many" : "Not enough";

        /* Work out how many we could have been passed. */
        for (int i = 0; i < numParams; i++) {
            SixModelObject param = params.at_pos_boxed(tc, i);
            param.get_attribute_native(tc, Ops.Parameter, "$!flags", HINT_flags);
            int flags = (int)tc.native_i;
            SixModelObject namedNames = param.get_attribute_boxed(tc,
                Ops.Parameter, "$!named_names", HINT_named_names);

            if (namedNames != null)
                continue;
            if ((flags & SIG_ELEM_SLURPY_NAMED) != 0)
                continue;
            if ((flags & SIG_ELEM_SLURPY_POS) != 0) {
                count = -1;
            }
            else if ((flags & SIG_ELEM_IS_OPTIONAL) != 0) {
                count++;
            }
            else {
                count++;
                arity++;
            }
        }

        /* Now generate decent error. */
        if (arity == count)
            return String.format(
                "%s positional parameters passed; got %d but expected %d",
                fail, numPosArgs, arity);
        else if (count == -1)
            return String.format(
                "%s positional parameters passed; got %d but expected at least %d",
                fail, numPosArgs, arity);
        else
            return String.format(
                "%s positional parameters passed; got %d but expected between %d and %d",
                fail, numPosArgs, arity, count);
    }
    
    /* Returns an appropriate failure mode (junction fail or normal fail). */
    private static int junc_or_fail(SixModelObject value) {
        if (value.st.WHAT == Ops.Junction)
            return BIND_RESULT_JUNCTION;
        else
            return BIND_RESULT_FAIL;
    }
    
    /* Binds a single argument into the lexpad, after doing any checks that are
     * needed. Also handles any type captures. If there is a sub signature, then
     * re-enters the binder. Returns one of the BIND_RESULT_* codes. */
    private static int bindOneParam(ThreadContext tc, CallFrame cf, SixModelObject param,
            Object origArg, byte origFlag, boolean noNomTypeCheck, boolean needError) {
        /* Get parameter flags and variable name. */
        param.get_attribute_native(tc, Ops.Parameter, "$!flags", HINT_flags);
        int paramFlags = (int)tc.native_i;
        param.get_attribute_native(tc, Ops.Parameter, "$!variable_name", HINT_variable_name);
        String varName = tc.native_s;
        
        /* We'll put the value to bind into one of the following locals, and
         * flag will indicate what type of thing it is. */
        int flag;
        long arg_i = 0;
        double arg_n = 0.0;
        String arg_s = null;
        SixModelObject arg_o = null;
        
        /* Check if boxed/unboxed expections are met. */
        int desiredNative = paramFlags & SIG_ELEM_NATIVE_VALUE;
        int gotNative = origFlag & 7;
        if (desiredNative == 0 && gotNative == CallSiteDescriptor.ARG_OBJ) {
            flag = gotNative;
            arg_o = (SixModelObject)origArg;
        }
        else if (desiredNative == SIG_ELEM_NATIVE_INT_VALUE && gotNative == CallSiteDescriptor.ARG_INT) {
            flag = gotNative;
            arg_i = (long)origArg;
        }
        else if (desiredNative == SIG_ELEM_NATIVE_NUM_VALUE && gotNative == CallSiteDescriptor.ARG_NUM) {
            flag = gotNative;
            arg_n = (double)origArg;
        }
        else if (desiredNative == SIG_ELEM_NATIVE_STR_VALUE && gotNative == CallSiteDescriptor.ARG_STR) {
            flag = gotNative;
            arg_s = (String)origArg;
        }
        else if (desiredNative == 0) {
            /* We need to do a boxing operation. */
            flag = CallSiteDescriptor.ARG_OBJ;
            arg_o = createBox(tc, origArg, gotNative);
        }
        else {
            /* We need to do an unboxing opeation. */
            SixModelObject decontValue = org.perl6.nqp.runtime.Ops.decont((SixModelObject)origArg, tc);
            StorageSpec spec = decontValue.st.REPR.get_storage_spec(tc, decontValue.st);
            switch (desiredNative) {
                case SIG_ELEM_NATIVE_INT_VALUE:
                    if ((spec.can_box & StorageSpec.CAN_BOX_INT) != 0) {
                        flag = CallSiteDescriptor.ARG_INT;
                        arg_i = decontValue.get_int(tc);
                    }
                    else {
                        if (needError)
                            lastErrors.put(tc, String.format(
                                "Cannot unbox argument to '%s' as a native int",
                                varName));
                        return BIND_RESULT_FAIL;
                    }
                    break;
                case SIG_ELEM_NATIVE_NUM_VALUE:
                    if ((spec.can_box & StorageSpec.CAN_BOX_NUM) != 0) {
                        flag = CallSiteDescriptor.ARG_NUM;
                        arg_n = decontValue.get_num(tc);
                    }
                    else {
                        if (needError)
                            lastErrors.put(tc, String.format(
                                "Cannot unbox argument to '%s' as a native num",
                                varName));
                        return BIND_RESULT_FAIL;
                    }
                    break;
                case SIG_ELEM_NATIVE_STR_VALUE:
                    if ((spec.can_box & StorageSpec.CAN_BOX_STR) != 0) {
                        flag = CallSiteDescriptor.ARG_STR;
                        arg_s = decontValue.get_str(tc);
                    }
                    else {
                        if (needError)
                            lastErrors.put(tc, String.format(
                                "Cannot unbox argument to '%s' as a native str",
                                varName));
                        return BIND_RESULT_FAIL;
                    }
                    break;
                default:
                    if (needError)
                        lastErrors.put(tc, String.format(
                            "Cannot unbox argument to '%s' as a native type",
                            varName));
                    return BIND_RESULT_FAIL;
            }
        }
        
        /* By this point, we'll either have an object that we might be able to
         * bind if it passes the type check, or a native value that needs no
         * further checking. */
        SixModelObject decontValue = null;
        if (flag == CallSiteDescriptor.ARG_OBJ) {
            /* We need to work on the decontainerized value. */
            decontValue = org.perl6.nqp.runtime.Ops.decont(arg_o, tc);
            
            /* HLL map it as needed. */
            decontValue = org.perl6.nqp.runtime.Ops.hllize(decontValue, tc);
            
            /* Skip nominal type check if not needed. */
            if (!noNomTypeCheck) {
                System.err.println("Parameter type checking NYI");
            }
        }
        
        /* TODO: Type captures. */
        
        /* TODO: Coercions. */
        
        /* If it's not got attributive binding, we'll go about binding it into the
         * lex pad. */
        if ((paramFlags & SIG_ELEM_BIND_ATTRIBUTIVE) == 0 && varName != null) {
            /* Is it native? If so, just go ahead and bind it. */
            StaticCodeInfo sci = cf.codeRef.staticInfo;
            if (flag != CallSiteDescriptor.ARG_OBJ) {
                switch (flag) {
                    case CallSiteDescriptor.ARG_INT:
                        cf.iLex[sci.iTryGetLexicalIdx(varName)] = arg_i;
                        break;
                    case CallSiteDescriptor.ARG_NUM:
                        cf.nLex[sci.nTryGetLexicalIdx(varName)] = arg_n;
                        break;
                    case CallSiteDescriptor.ARG_STR:
                        cf.sLex[sci.sTryGetLexicalIdx(varName)] = arg_s;
                        break;
                }
            }
            
            /* Otherwise it's some objecty case. */
            else if ((paramFlags & SIG_ELEM_IS_RW) != 0) {
                /* XXX TODO Check if rw flag is set; also need to have a
                 * wrapper container that carries extra constraints. */
                cf.oLex[sci.oTryGetLexicalIdx(varName)] = decontValue;
            }
            else if ((paramFlags & SIG_ELEM_IS_PARCEL) != 0) {
                /* Just bind the thing as is into the lexpad. */
                cf.oLex[sci.oTryGetLexicalIdx(varName)] = decontValue;
            }
            else {
                /* If it's an array, copy means make a new one and store,
                 * and a normal bind is a straightforward binding plus
                 * adding a constraint. */
                if ((paramFlags & SIG_ELEM_ARRAY_SIGIL) != 0) {
                    SixModelObject bindee = decontValue;
                    if ((paramFlags & SIG_ELEM_IS_COPY) != 0) {
                        throw new RuntimeException("is copy array param NYI");
                    }
                    cf.oLex[sci.oTryGetLexicalIdx(varName)] = bindee;
                }
                
                /* If it's a hash, similar approach to array. */
                else if ((paramFlags & SIG_ELEM_HASH_SIGIL) != 0) {
                    SixModelObject bindee = decontValue;
                    if ((paramFlags & SIG_ELEM_IS_COPY) != 0) {
                        throw new RuntimeException("is copy hash param NYI");
                    }
                    cf.oLex[sci.oTryGetLexicalIdx(varName)] = bindee;
                }
                
                /* If it's a scalar, we always need to wrap it into a new
                 * container and store it, for copy or ro case (the rw bit
                 * in the container descriptor takes care of the rest). */
                else {
                    throw new RuntimeException("scalar params NYI");
                }
            }
        }
        
        System.err.println("bindOneParam NYFI");
        return BIND_RESULT_OK;
    }
    
    /* Takes a signature along with positional and named arguments and binds them
     * into the provided callframe. Returns BIND_RESULT_OK if binding works out,
     * BIND_RESULT_FAIL if there is a failure and BIND_RESULT_JUNCTION if the
     * failure was because of a Junction being passed (meaning we need to auto-thread). */
    public static int bind(ThreadContext tc, CallFrame cf, SixModelObject params,
            CallSiteDescriptor csd, Object[] args,
            boolean noNomTypeCheck, boolean needError) {
        int bindFail = BIND_RESULT_OK;
        int curPosArg = 0;
        
        /* If we have a |$foo that's followed by slurpies, then we can suppress
         * any future arity checks. */
        boolean suppressArityFail = false;
        
        /* Now we'll walk through the signature and go about binding things. */
        int numPosArgs = csd.numPositionals;
        long numParams = params.elems(tc);
        for (long i = 0; i < numParams; i++) {
            /* Get parameter, its flags and any named names. */
            SixModelObject param = params.at_pos_boxed(tc, i);
            param.get_attribute_native(tc, Ops.Parameter, "$!flags", HINT_flags);
            int flags = (int)tc.native_i;
            SixModelObject namedNames = param.get_attribute_boxed(tc,
                Ops.Parameter, "$!named_names", HINT_named_names);
            
            /* Is it looking for us to bind a capture here? */
            if ((flags & SIG_ELEM_IS_CAPTURE) != 0) {
                /* Capture the arguments from this point forwards into a Capture.
                 * Of course, if there's no variable name we can (cheaply) do pretty
                 * much nothing. */
                param.get_attribute_native(tc, Ops.Parameter, "$!variable_name", HINT_variable_name);
                if (tc.native_s == null) {
                   bindFail = BIND_RESULT_OK;
                }
                else {
                    throw new RuntimeException("Capture param binding NYI");
                }
                if (bindFail != 0) {
                    return bindFail;
                }
                else if (i + 1 == numParams) {
                    /* Since a capture acts as "the ultimate slurpy" in a sense, if
                     * this is the last parameter in the signature we can return
                     * success right off the bat. */
                    return BIND_RESULT_OK;
                }
                else {
                    SixModelObject nextParam = params.at_pos_boxed(tc, i + 1);
                    nextParam.get_attribute_native(tc, Ops.Parameter, "$!flags", HINT_flags);
                    if (((int)tc.native_i & (SIG_ELEM_SLURPY_POS | SIG_ELEM_SLURPY_NAMED)) != 0)
                        suppressArityFail = true;
                }
            }
            
            /* Could it be a named slurpy? */
            else if ((flags & SIG_ELEM_SLURPY_NAMED) != 0) {
                System.err.println("Slurpy named param NYI");
            }
            
            /* Otherwise, maybe it's a positional of some kind. */
            else if (namedNames == null) {
                /* Slurpy or LoL-slurpy? */
                if ((flags & (SIG_ELEM_SLURPY_POS | SIG_ELEM_SLURPY_LOL)) != 0) {
                    System.err.println("Slurpy pos param NYI");
                }
                
                /* Otherwise, a positional. */
                else {
                    /* Do we have a value?. */
                    if (curPosArg < numPosArgs) {
                        /* Easy - just bind that. */
                        bindFail = bindOneParam(tc, cf, param, args[curPosArg],
                            csd.argFlags[curPosArg], noNomTypeCheck, needError);
                        if (bindFail != 0)
                            return bindFail;
                        curPosArg++;
                    }
                    else {
                        /* No value. If it's optional, fetch a default and bind that;
                         * if not, we're screwed. Note that we never nominal type check
                         * an optional with no value passed. */
                        if ((flags & SIG_ELEM_IS_OPTIONAL) != 0) {
                            System.err.println("Optional parameters NYI");
                        }
                        else {
                            if (needError)
                                lastErrors.put(tc, arityFail(tc, params, (int)numParams, numPosArgs, false));
                            return BIND_RESULT_FAIL;
                        }
                    }
                }
            }
            
            /* Else, it's a non-slurpy named. */
            else {
                System.err.println("Named arg binding NYI");
            }
        }
        
        
        /* XXX TODO. */
        return BIND_RESULT_OK;
    }
    
    public static String lastError(ThreadContext tc) {
        return lastErrors.get(tc);
    }
}
