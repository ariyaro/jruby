/*
 ***** BEGIN LICENSE BLOCK *****
 * Version: EPL 2.0/GPL 2.0/LGPL 2.1
 *
 * The contents of this file are subject to the Eclipse Public
 * License Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of
 * the License at http://www.eclipse.org/legal/epl-v20.html
 *
 * Software distributed under the License is distributed on an "AS
 * IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * rights and limitations under the License.
 *
 * Copyright (C) 2006, 2007 Ola Bini <ola@ologix.com>
 * Copyright (C) 2007 Nick Sieger <nicksieger@gmail.com>
 * Copyright (C) 2008 Vladimir Sizikov <vsizikov@gmail.com>
 * Copyright (C) 2009 Joseph LaFata <joe@quibb.org>
 *
 * Alternatively, the contents of this file may be used under the terms of
 * either of the GNU General Public License Version 2 or later (the "GPL"),
 * or the GNU Lesser General Public License Version 2.1 or later (the "LGPL"),
 * in which case the provisions of the GPL or the LGPL are applicable instead
 * of those above. If you wish to allow use of your version of this file only
 * under the terms of either the GPL or the LGPL, and not to allow others to
 * use your version of this file under the terms of the EPL, indicate your
 * decision by deleting the provisions above and replace them with the notice
 * and other provisions required by the GPL or the LGPL. If you do not delete
 * the provisions above, a recipient may use your version of this file under
 * the terms of any one of the EPL, the GPL or the LGPL.
 ***** END LICENSE BLOCK *****/

package org.jruby.ext.digest;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.Provider;
import java.util.HashMap;
import java.util.Map;

import org.jcodings.specific.USASCIIEncoding;
import org.jruby.Ruby;
import org.jruby.RubyClass;
import org.jruby.RubyFixnum;
import org.jruby.RubyModule;
import org.jruby.RubyObject;
import org.jruby.RubyString;

import org.jruby.anno.JRubyClass;
import org.jruby.anno.JRubyMethod;
import org.jruby.anno.JRubyModule;
import org.jruby.exceptions.RaiseException;
import org.jruby.runtime.Arity;
import org.jruby.runtime.Block;
import org.jruby.runtime.ThreadContext;
import org.jruby.runtime.Visibility;
import org.jruby.runtime.builtin.IRubyObject;
import org.jruby.util.ArraySupport;
import org.jruby.util.ByteList;
import org.jruby.util.log.Logger;
import org.jruby.util.log.LoggerFactory;

/**
 * @author <a href="mailto:ola.bini@ki.se">Ola Bini</a>
 */
@JRubyModule(name="Digest")
public class RubyDigest {

    private static final Map<String, MessageDigest> CLONEABLE_DIGESTS = new HashMap<String, MessageDigest>(8, 1);
    static {
        // standard digests from JCA specification; if we can retrieve and clone, save them
        for (String name : new String[] {"MD2", "MD5", "SHA-1", "SHA-256", "SHA-384", "SHA-512"}) {
            try {
                MessageDigest digest = MessageDigest.getInstance(name);
                digest.clone();
                CLONEABLE_DIGESTS.put(name, digest);
            }
            catch (Exception e) {
                logger().debug(name + " not clonable", e);
            }
        }
    }

    private static Logger logger() { return LoggerFactory.getLogger(RubyDigest.class); }

    private static final String PROVIDER = "org.bouncycastle.jce.provider.BouncyCastleProvider";
    private static Provider provider = null;

    public static void createDigest(Ruby runtime) {
        try {
            provider = (Provider) Class.forName(PROVIDER).getConstructor().newInstance();
        }
        catch (Throwable t) { /* provider is not available */ }

        RubyModule mDigest = runtime.defineModule("Digest");
        mDigest.defineAnnotatedMethods(RubyDigest.class);
        RubyModule mDigestInstance = mDigest.defineModuleUnder("Instance");
        mDigestInstance.defineAnnotatedMethods(DigestInstance.class);
        RubyClass cDigestClass = mDigest.defineClassUnder("Class", runtime.getObject(), DigestClass::new);
        cDigestClass.defineAnnotatedMethods(DigestClass.class);
        cDigestClass.includeModule(mDigestInstance);
        RubyClass cDigestBase = mDigest.defineClassUnder("Base", cDigestClass, DigestBase::new);
        cDigestBase.defineAnnotatedMethods(DigestBase.class);
    }

    private static MessageDigest createMessageDigest(final String name) throws NoSuchAlgorithmException {
        MessageDigest cloneable = CLONEABLE_DIGESTS.get(name);
        if (cloneable != null) {
            try {
                return (MessageDigest) cloneable.clone();
            }
            catch (CloneNotSupportedException e) {
                // should never happen, since we tested it in static init
            }
        }

        // fall back on JCA mechanisms for getting a digest
        if (provider != null) {
            try {
                return MessageDigest.getInstance(name, provider);
            }
            catch (NoSuchAlgorithmException e) {
                // bouncy castle doesn't support algorithm
            }
        }

        // fall back to default JCA providers
        return MessageDigest.getInstance(name);
    }

    private final static byte[] digits = {
        '0', '1', '2', '3', '4', '5',
        '6', '7', '8', '9', 'a', 'b',
        'c', 'd', 'e', 'f', 'g', 'h',
        'i', 'j', 'k', 'l', 'm', 'n',
        'o', 'p', 'q', 'r', 's', 't',
        'u', 'v', 'w', 'x', 'y', 'z'
    };

    private static ByteList toHex(byte[] val) {
        ByteList byteList = new ByteList(val.length * 2);
        for (int i = 0, j = val.length; i < j; i++) {
            int b = val[i] & 0xFF;
            byteList.append(digits[b >> 4]);
            byteList.append(digits[b & 0xF]);
        }
        return byteList;
    }

    private static RubyString toHexString(Ruby runtime, byte[] val) {
        return RubyString.newStringNoCopy(runtime, new ByteList(ByteList.plain(toHex(val)), USASCIIEncoding.INSTANCE));
    }

    @JRubyMethod(name = "hexencode", required = 1, meta = true)
    public static RubyString hexencode(IRubyObject self, IRubyObject arg) {
        return toHexString(self.getRuntime(), arg.convertToString().getBytes());
    }

    @JRubyMethod(name = "bubblebabble", required = 1, meta = true)
    public static RubyString bubblebabble(IRubyObject recv, IRubyObject arg) {
        final ByteList bytes = arg.convertToString().getByteList();
        return RubyString.newString(recv.getRuntime(), BubbleBabble.bubblebabble(bytes.unsafeBytes(), bytes.begin(), bytes.length()));
    }

    private static class Metadata {

        private final String name;
        private final int blockLength;

        Metadata(String name, int blockLength) {
            this.name = name;
            this.blockLength = blockLength;
        }

        String getName() {
            return name;
        }

        int getBlockLength() {
            return blockLength;
        }
    }


    @JRubyClass(name="Digest::MD5", parent="Digest::Base")
    public static class MD5 {}
    @JRubyClass(name="Digest::RMD160", parent="Digest::Base")
    public static class RMD160 {}
    @JRubyClass(name="Digest::SHA1", parent="Digest::Base")
    public static class SHA1 {}
    @JRubyClass(name="Digest::SHA256", parent="Digest::Base")
    public static class SHA256 {}
    @JRubyClass(name="Digest::SHA384", parent="Digest::Base")
    public static class SHA384 {}
    @JRubyClass(name="Digest::SHA512", parent="Digest::Base")
    public static class SHA512 {}

    public static void createDigestMD5(Ruby runtime) {
        runtime.getLoadService().require("digest");
        RubyModule Digest = runtime.getModule("Digest");
        RubyClass Base = Digest.getClass("Base");
        RubyClass MD5 = Digest.defineClassUnder("MD5", Base, Base.getAllocator());
        MD5.setInternalVariable("metadata", new Metadata("MD5", 64));
    }

    public static void createDigestRMD160(Ruby runtime) {
        runtime.getLoadService().require("digest");
        if(provider == null) {
            throw runtime.newLoadError("RMD160 not supported without BouncyCastle");
        }
        RubyModule Digest = runtime.getModule("Digest");
        RubyClass Base = Digest.getClass("Base");
        RubyClass RMD160 = Digest.defineClassUnder("RMD160", Base, Base.getAllocator());
        RMD160.setInternalVariable("metadata", new Metadata("RIPEMD160", 64));
    }

    public static void createDigestSHA1(Ruby runtime) {
        runtime.getLoadService().require("digest");
        RubyModule Digest = runtime.getModule("Digest");
        RubyClass Base = Digest.getClass("Base");
        RubyClass SHA1 = Digest.defineClassUnder("SHA1", Base, Base.getAllocator());
        SHA1.setInternalVariable("metadata", new Metadata("SHA1", 64));
    }

    public static void createDigestSHA2(Ruby runtime) {
        runtime.getLoadService().require("digest");
        try {
            createMessageDigest("SHA-256");
        }
        catch (NoSuchAlgorithmException e) {
            RaiseException ex = runtime.newLoadError("SHA2 not supported");
            ex.initCause(e);
            throw ex;
        }
        final RubyModule Digest = runtime.getModule("Digest");
        final RubyClass Base = Digest.getClass("Base");
        RubyClass SHA256 = Digest.defineClassUnder("SHA256", Base, Base.getAllocator());
        SHA256.setInternalVariable("metadata", new Metadata("SHA-256", 64));
        RubyClass SHA384 = Digest.defineClassUnder("SHA384", Base, Base.getAllocator());
        SHA384.setInternalVariable("metadata", new Metadata("SHA-384", 128));
        RubyClass SHA512 = Digest.defineClassUnder("SHA512", Base, Base.getAllocator());
        SHA512.setInternalVariable("metadata", new Metadata("SHA-512", 128));
    }

    public static void createDigestBubbleBabble(Ruby runtime) {
        runtime.getLoadService().require("digest");
        RubyModule Digest = runtime.getModule("Digest");
        RubyClass Base = Digest.getClass("Base");
        RubyClass MD5 = Digest.defineClassUnder("BubbleBabble", Base, Base.getAllocator());
        MD5.setInternalVariable("metadata", new Metadata("BubbleBabble", 64));
    }

    @JRubyModule(name = "Digest::Instance")
    public static class DigestInstance {

        private static IRubyObject throwUnimplError(IRubyObject self, String name) {
            throw self.getRuntime().newRuntimeError(String.format("%s does not implement %s()", self.getMetaClass().getRealClass().getName(), name));
        }

        /* instance methods that should be overridden */
        @JRubyMethod(name = {"update", "<<"}, required = 1)
        public static IRubyObject update(ThreadContext context, IRubyObject self, IRubyObject arg) {
            return throwUnimplError(self, "update");
        }

        @JRubyMethod()
        public static IRubyObject finish(ThreadContext context, IRubyObject self) {
            return throwUnimplError(self, "finish");
        }

        @JRubyMethod()
        public static IRubyObject reset(ThreadContext context, IRubyObject self) {
            return throwUnimplError(self, "reset");
        }

        @JRubyMethod()
        public static IRubyObject digest_length(ThreadContext context, IRubyObject self) {
            return digest(context, self, IRubyObject.NULL_ARRAY).convertToString().bytesize();
        }

        @JRubyMethod()
        public static IRubyObject block_length(ThreadContext context, IRubyObject self) {
            return throwUnimplError(self, "block_length");
        }

        /* instance methods that may be overridden */
        @JRubyMethod(name = "==", required = 1)
        public static IRubyObject op_equal(ThreadContext context, IRubyObject self, IRubyObject oth) {
            if(oth.isNil()) return context.fals;

            RubyString str1, str2;
            RubyModule instance = (RubyModule)context.runtime.getModule("Digest").getConstantAt("Instance");
            if (oth.getMetaClass().getRealClass().hasModuleInHierarchy(instance)) {
                str1 = digest(context, self, IRubyObject.NULL_ARRAY).convertToString();
                str2 = digest(context, oth, IRubyObject.NULL_ARRAY).convertToString();
            } else {
                str1 = to_s(context, self).convertToString();
                str2 = oth.convertToString();
            }
            boolean ret = str1.bytesize().eql(str2.bytesize()) && (str1.eql(str2));
            return ret ? context.tru : context.fals;
        }

        @JRubyMethod()
        public static IRubyObject inspect(ThreadContext context, IRubyObject self) {
            return RubyString.newStringNoCopy(self.getRuntime(), ByteList.plain("#<" + self.getMetaClass().getRealClass().getName() + ": " + hexdigest(context, self, IRubyObject.NULL_ARRAY) + ">"));
        }

        /* instance methods that need not usually be overridden */
        @JRubyMethod(name = "new")
        public static IRubyObject newObject(ThreadContext context, IRubyObject self) {
            return self.rbClone().callMethod(context, "reset");
        }

        @JRubyMethod(optional = 1, checkArity = false)
        public static IRubyObject digest(ThreadContext context, IRubyObject self, IRubyObject[] args) {
            int argc = Arity.checkArgumentCount(context, args, 0, 1);

            final IRubyObject value;
            if (args != null && argc > 0) {
                self.callMethod(context, "reset");
                self.callMethod(context, "update", args[0]);
                value = self.callMethod(context, "finish");
                self.callMethod(context, "reset");
            } else {
                IRubyObject clone = self.rbClone();
                value = clone.callMethod(context, "finish");
                clone.callMethod(context, "reset");
            }
            return value;
        }

        @JRubyMethod(name = "digest!")
        public static IRubyObject digest_bang(ThreadContext context, IRubyObject self) {
            IRubyObject value = self.callMethod(context, "finish");
            self.callMethod(context, "reset");
            return value;
        }

        @JRubyMethod(optional = 1, checkArity = false)
        public static IRubyObject hexdigest(ThreadContext context, IRubyObject self, IRubyObject[] args) {
            return toHexString(context.runtime, digest(context, self, args).convertToString().getBytes());
        }

        @JRubyMethod(name = "hexdigest!")
        public static IRubyObject hexdigest_bang(ThreadContext context, IRubyObject self) {
            return toHexString(context.runtime, digest_bang(context, self).convertToString().getBytes());
        }

        @JRubyMethod(name = "bubblebabble", required = 1, optional = 1, checkArity = false, meta = true)
        public static IRubyObject bubblebabble(ThreadContext context, IRubyObject recv, IRubyObject[] args, Block unusedBlock) {
            byte[] digest = recv.callMethod(context, "digest", args, Block.NULL_BLOCK).convertToString().getBytes();
            return RubyString.newString(recv.getRuntime(), BubbleBabble.bubblebabble(digest, 0, digest.length));
        }

        @JRubyMethod()
        public static IRubyObject to_s(ThreadContext context, IRubyObject self) {
            return self.callMethod(context, "hexdigest");
        }

        @JRubyMethod(name = {"length", "size"})
        public static IRubyObject length(ThreadContext context, IRubyObject self) {
            return self.callMethod(context, "digest_length");
        }
    }


    @JRubyClass(name="Digest::Class")
    public static class DigestClass extends RubyObject {
        public DigestClass(Ruby runtime, RubyClass type) {
            super(runtime, type);
        }

        @JRubyMethod(name = "digest", required = 1, rest = true, checkArity = false, meta = true)
        public static IRubyObject s_digest(ThreadContext context, IRubyObject recv, IRubyObject[] args, Block unusedBlock) {
            final Ruby runtime = context.runtime;
            if (args.length < 1) {
                throw runtime.newArgumentError("no data given");
            }
            RubyString str = args[0].convertToString();
            args = ArraySupport.newCopy(args, 1, args.length - 1); // skip first arg
            IRubyObject obj = ((RubyClass) recv).newInstance(context, args, Block.NULL_BLOCK);
            return obj.callMethod(context, "digest", str);
        }

        @JRubyMethod(name = "hexdigest", required = 1, optional = 1, checkArity = false, meta = true)
        public static IRubyObject s_hexdigest(ThreadContext context, IRubyObject recv, IRubyObject[] args, Block unusedBlock) {
            Ruby runtime = recv.getRuntime();
            byte[] digest = recv.callMethod(context, "digest", args, Block.NULL_BLOCK).convertToString().getBytes();
            return RubyDigest.toHexString(runtime, digest);
        }

        @JRubyMethod(name = "bubblebabble", required = 1, meta = true)
        public static RubyString bubblebabble(IRubyObject recv, IRubyObject arg) {
            byte[] digest = recv.callMethod(recv.getRuntime().getCurrentContext(), "digest", arg).convertToString().getBytes();
            return RubyString.newString(recv.getRuntime(), BubbleBabble.bubblebabble(digest, 0, digest.length));
        }
    }


    @JRubyClass(name="Digest::Base")
    public static class DigestBase extends RubyObject {
        private MessageDigest algo;
        private int blockLength = 0;

        public DigestBase(Ruby runtime, RubyClass type) {
            super(runtime,type);

            if(type == runtime.getModule("Digest").getClass("Base")) {
                throw runtime.newNotImplementedError("Digest::Base is an abstract class");
            }

            Metadata metadata = getMetadata(type);
            if(metadata == null) {
                throw runtime.newNotImplementedError("the " + type + "() function is unimplemented on this machine");
            }

            try {
                setAlgorithm(metadata);
            } catch(NoSuchAlgorithmException e) {
                throw runtime.newNotImplementedError("the " + type + "() function is unimplemented on this machine");
            }

        }

        // if subclass extends particular digest we need to walk to find it...we should rearchitect digest to avoid walking type systems
        private Metadata getMetadata(RubyModule type) {
            for (RubyModule current = type; current != null; current = current.getSuperClass()) {
                Metadata metadata = (Metadata) current.getInternalVariable("metadata");

                if (metadata != null) return metadata;
            }

            return null;
        }

        @JRubyMethod(required = 1, visibility = Visibility.PRIVATE)
        @Override
        public IRubyObject initialize_copy(IRubyObject obj) {
            if (this == obj) return this;

            DigestBase from = (DigestBase) obj;
            from.checkFrozen();

            try {
                this.algo = (MessageDigest) from.algo.clone();
            }
            catch (CloneNotSupportedException e) {
                String name = from.algo.getAlgorithm();
                throw getRuntime().newTypeError("Could not initialize copy of digest (" + name + ")");
            }
            return this;
        }

        @JRubyMethod(name = {"update", "<<"}, required = 1)
        public IRubyObject update(IRubyObject obj) {
            ByteList bytes = obj.convertToString().getByteList();
            algo.update(bytes.getUnsafeBytes(), bytes.getBegin(), bytes.getRealSize());
            return this;
        }

        @JRubyMethod()
        public IRubyObject finish() {
            IRubyObject digest = RubyString.newStringNoCopy(getRuntime(), algo.digest());
            algo.reset();
            return digest;
        }

        @JRubyMethod()
        public IRubyObject digest_length() {
            return RubyFixnum.newFixnum(getRuntime(), algo.getDigestLength());
        }

        @JRubyMethod()
        public IRubyObject block_length() {
            if (blockLength == 0) {
                throw getRuntime().newRuntimeError(
                        this.getMetaClass() + " doesn't implement block_length()");
            }
            return RubyFixnum.newFixnum(getRuntime(), blockLength);
        }

        @JRubyMethod()
        public IRubyObject reset() {
            algo.reset();
            return this;
        }

        @JRubyMethod()
        public IRubyObject bubblebabble(ThreadContext context) {
            final byte[] digest = algo.digest();
            return RubyString.newString(context.runtime, BubbleBabble.bubblebabble(digest, 0, digest.length));
        }

        private void setAlgorithm(Metadata metadata) throws NoSuchAlgorithmException {
           this.algo = createMessageDigest(metadata.getName());
           this.blockLength = metadata.getBlockLength();
        }

    }
}// RubyDigest
