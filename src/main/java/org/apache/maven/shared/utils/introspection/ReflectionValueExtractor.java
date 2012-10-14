package org.apache.maven.shared.utils.introspection;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.StringTokenizer;
import java.util.WeakHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.maven.shared.utils.StringUtils;
import org.apache.maven.shared.utils.introspection.MethodMap.AmbiguousException;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
 * <p>Using simple dotted expressions to extract the values from an Object instance,
 * For example we might want to extract a value like: <code>project.build.sourceDirectory</code></p>
 * <p/>
 * <p>The implementation supports indexed, nested and mapped properties similar to the JSP way.</p>
 *
 * @author <a href="mailto:jason@maven.org">Jason van Zyl </a>
 * @author <a href="mailto:vincent.siveton@gmail.com">Vincent Siveton</a>
 * @version $Id$
 * @see <a href="http://struts.apache.org/1.x/struts-taglib/indexedprops.html">http://struts.apache.org/1.x/struts-taglib/indexedprops.html</a>
 */
public class ReflectionValueExtractor
{
    private static final Class<?>[] CLASS_ARGS = new Class[0];

    private static final Object[] OBJECT_ARGS = new Object[0];

    /**
     * Use a WeakHashMap here, so the keys (Class objects) can be garbage collected.
     * This approach prevents permgen space overflows due to retention of discarded
     * classloaders.
     */
    private static final Map<Class<?>, ClassMap> classMaps = new WeakHashMap<Class<?>, ClassMap>();

    /**
     * Indexed properties pattern, ie <code>(\\w+)\\[(\\d+)\\]</code>
     */
    private static final Pattern INDEXED_PROPS = Pattern.compile( "(\\w+)\\[(\\d+)\\]" );

    /**
     * Indexed properties pattern, ie <code>(\\w+)\\((.+)\\)</code>
     */
    private static final Pattern MAPPED_PROPS = Pattern.compile( "(\\w+)\\((.+)\\)" );

    private ReflectionValueExtractor()
    {
    }

    /**
     * <p>The implementation supports indexed, nested and mapped properties.</p>
     * <p/>
     * <ul>
     * <li>nested properties should be defined by a dot, i.e. "user.address.street"</li>
     * <li>indexed properties (java.util.List or array instance) should be contains <code>(\\w+)\\[(\\d+)\\]</code>
     * pattern, i.e. "user.addresses[1].street"</li>
     * <li>mapped properties should be contains <code>(\\w+)\\((.+)\\)</code> pattern, i.e. "user.addresses(myAddress).street"</li>
     * <ul>
     *
     * @param expression not null expression
     * @param root       not null object
     * @return the object defined by the expression
     * @throws IntrospectionException if any
     */
    public static Object evaluate( @Nonnull String expression, @Nullable Object root )
        throws IntrospectionException
    {
        return evaluate( expression, root, true );
    }

    /**
     * <p>The implementation supports indexed, nested and mapped properties.</p>
     * <p/>
     * <ul>
     * <li>nested properties should be defined by a dot, i.e. "user.address.street"</li>
     * <li>indexed properties (java.util.List or array instance) should be contains <code>(\\w+)\\[(\\d+)\\]</code>
     * pattern, i.e. "user.addresses[1].street"</li>
     * <li>mapped properties should be contains <code>(\\w+)\\((.+)\\)</code> pattern, i.e. "user.addresses(myAddress).street"</li>
     * <ul>
     *
     * @param expression not null expression
     * @param root       not null object
     * @return the object defined by the expression
     * @throws IntrospectionException if any
     */
    public static Object evaluate( @Nonnull String expression, @Nullable Object root, boolean trimRootToken )
        throws IntrospectionException
    {
        // if the root token refers to the supplied root object parameter, remove it.
        if ( trimRootToken )
        {
            expression = expression.substring( expression.indexOf( '.' ) + 1 );
        }

        Object value = root;

        // ----------------------------------------------------------------------
        // Walk the dots and retrieve the ultimate value desired from the
        // MavenProject instance.
        // ----------------------------------------------------------------------

        StringTokenizer parser = new StringTokenizer( expression, "." );

        while ( parser.hasMoreTokens() )
        {
            // if we have nothing, stop now
            if ( value == null )
            {
                return null;
            }

            String token = parser.nextToken();

            ClassMap classMap = getClassMap( value.getClass() );

            Method method;
            Object[] localParams = OBJECT_ARGS;

            // do we have an indexed property?
            Matcher matcher = INDEXED_PROPS.matcher( token );
            if ( matcher.find() )
            {
                String methodBase = StringUtils.capitalizeFirstLetter( matcher.group( 1 ) );
                String methodName = "get" + methodBase;
                try
                {
                    method = classMap.findMethod( methodName, CLASS_ARGS );
                }
                catch ( AmbiguousException e )
                {
                    throw new IntrospectionException( e );
                }
                
                try
                {
                    value = method.invoke( value, OBJECT_ARGS );
                }
                catch ( IllegalArgumentException e )
                {
                    throw new IntrospectionException( e );
                }
                catch ( IllegalAccessException e )
                {
                    throw new IntrospectionException( e );
                }
                catch ( InvocationTargetException e )
                {
                    throw new IntrospectionException( e );
                }                
                
                classMap = getClassMap( value.getClass() );

                if ( classMap.getCachedClass().isArray() )
                {
                    value = Arrays.asList( (Object[]) value );
                    classMap = getClassMap( value.getClass() );
                }

                if ( value instanceof List )
                {
                    // use get method on List interface
                    localParams = new Object[1];
                    localParams[0] = Integer.valueOf( matcher.group( 2 ) );
                    try
                    {
                        method = classMap.findMethod( "get", localParams );
                    }
                    catch ( AmbiguousException e )
                    {
                        throw new IntrospectionException( e );
                    }
                }
                else
                {
                    throw new IntrospectionException( "The token '" + token
                                             + "' refers to a java.util.List or an array, but the value seems is an instance of '"
                                             + value.getClass() + "'." );
                }
            }
            else
            {
                // do we have a mapped property?
                matcher = MAPPED_PROPS.matcher( token );
                if ( matcher.find() )
                {
                    String methodBase = StringUtils.capitalizeFirstLetter( matcher.group( 1 ) );
                    String methodName = "get" + methodBase;
                    try
                    {
                        method = classMap.findMethod( methodName, CLASS_ARGS );
                    }
                    catch ( AmbiguousException e )
                    {
                        throw new IntrospectionException( e );
                    }
                    
                    try
                    {
                        value = method.invoke( value, OBJECT_ARGS );
                    }
                    catch ( IllegalArgumentException e )
                    {
                        throw new IntrospectionException( e );
                    }
                    catch ( IllegalAccessException e )
                    {
                        throw new IntrospectionException( e );
                    }
                    catch ( InvocationTargetException e )
                    {
                        throw new IntrospectionException( e );
                    }
                    classMap = getClassMap( value.getClass() );

                    if ( value instanceof Map )
                    {
                        // use get method on List interface
                        localParams = new Object[1];
                        localParams[0] = matcher.group( 2 );
                        try
                        {
                            method = classMap.findMethod( "get", localParams );
                        }
                        catch ( AmbiguousException e )
                        {
                            throw new IntrospectionException( e );
                        }
                    }
                    else
                    {
                        throw new IntrospectionException( "The token '" + token
                                                 + "' refers to a java.util.Map, but the value seems is an instance of '"
                                                 + value.getClass() + "'." );
                    }
                }
                else
                {
                    String methodBase = StringUtils.capitalizeFirstLetter( token );
                    String methodName = "get" + methodBase;
                    try
                    {
                        method = classMap.findMethod( methodName, CLASS_ARGS );
                    }
                    catch ( AmbiguousException e )
                    {
                        throw new IntrospectionException( e );
                    }

                    if ( method == null )
                    {
                        // perhaps this is a boolean property??
                        methodName = "is" + methodBase;

                        try
                        {
                            method = classMap.findMethod( methodName, CLASS_ARGS );
                        }
                        catch ( AmbiguousException e )
                        {
                            throw new IntrospectionException( e );
                        }
                    }
                }
            }

            if ( method == null )
            {
                return null;
            }

            try
            {
                value = method.invoke( value, localParams );
            }
            catch ( InvocationTargetException e )
            {
                // catch array index issues gracefully, otherwise release
                if ( e.getCause() instanceof IndexOutOfBoundsException )
                {
                    return null;
                }

                throw new IntrospectionException( e );
            }
            catch ( IllegalArgumentException e )
            {
                throw new IntrospectionException( e );
            }
            catch ( IllegalAccessException e )
            {
                throw new IntrospectionException( e );
            }
        }

        return value;
    }

    private static ClassMap getClassMap( Class<?> clazz )
    {
        ClassMap classMap = classMaps.get( clazz );

        if ( classMap == null )
        {
            classMap = new ClassMap( clazz );

            classMaps.put( clazz, classMap );
        }

        return classMap;
    }
}
