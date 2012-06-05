package org.languagetool.rules.spelling.hunspell;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Scanner;

import morfologik.util.BufferUtils;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.PointerByReference;

/**
 * The simple hunspell library frontend which takes care of creating
 * and singleton'ing the library instance (no need to load it more than once
 * per process).
 *
 * The Hunspell java bindings are licensed under the same terms as Hunspell itself (GPL/LGPL/MPL tri-license),
 * see the file COPYING.txt in the root of the distribution for the exact terms.
 *
 * @author Flemming Frandsen (flfr at stibo dot com)
 */

public class Hunspell {

    /**
     * The Singleton instance of Hunspell
     */
    private static Hunspell hunspell = null;

    /**
     * The native library instance, created by JNA.
     */
    private HunspellLibrary hsl = null;

    /**
     * The library file that was loaded.
     */
    private String libFile;

    /**
     * The instance of the HunspellManager, looks for the native lib in the
     * default directories
     */
    public static Hunspell getInstance() throws UnsatisfiedLinkError, UnsupportedOperationException { 
        return getInstance(null);
    }

    /**
     * The instance of the HunspellManager, looks for the native lib in
     * the directory specified.
     *
     * @param libDir Optional absolute directory where the native lib can be found. 
     */
    public static Hunspell getInstance(String libDir) throws UnsatisfiedLinkError, UnsupportedOperationException { 
        if (hunspell != null) {
            return hunspell;
        }

        hunspell = new Hunspell(libDir);
        return hunspell;
    }

    protected void tryLoad(String libFile) throws UnsupportedOperationException {
        hsl = (HunspellLibrary)Native.loadLibrary(libFile, HunspellLibrary.class);
    }


    /**
     * Constructor for the library, loads the native lib.
     *
     * Loading is done in the first of the following three ways that works:
     * 1) Unmodified load in the provided directory.
     * 2) libFile stripped back to the base name (^lib(.*)\.so on unix)
     * 3) The library is searched for in the classpath, extracted to disk and loaded.
     *
     * @param libDir Optional absolute directory where the native lib can be found. 
     * @throws UnsupportedOperationException if the OS or architecture is simply not supported.
     */
    protected Hunspell(String libDir) throws UnsatisfiedLinkError, UnsupportedOperationException {

        libFile = libDir != null ? libDir+"/"+libName() : libNameBare();
        try {	   
            hsl = (HunspellLibrary)Native.loadLibrary(libFile, HunspellLibrary.class);
        } catch (UnsatisfiedLinkError urgh) {

            // Oh dear, the library was not found in the file system, let's try the classpath
            libFile = libName();
            InputStream is = Hunspell.class.getResourceAsStream("/"+libFile);
            if (is == null) {
                throw new UnsatisfiedLinkError("Can't find "+libFile+
                        " in the filesystem nor in the classpath\n"+
                        urgh);
            }

            // Extract the library from the classpath into a temp file.
            File lib;
            FileOutputStream fos = null;
            try {
                lib = File.createTempFile("jna", "."+libFile);
                lib.deleteOnExit();
                fos = new FileOutputStream(lib);
                int count;
                byte[] buf = new byte[1024];
                while ((count = is.read(buf, 0, buf.length)) > 0) {
                    fos.write(buf, 0, count);
                }

            } catch(IOException e) {
                throw new Error("Failed to create temporary file for "+libFile, e);

            } finally {
                try { is.close(); } catch(IOException e) { }
                if (fos != null) {
                    try { fos.close(); } catch(IOException e) { }
                }
            }
            //System.out.println("Loading temp lib: "+lib.getAbsolutePath());
            hsl = (HunspellLibrary)Native.loadLibrary(lib.getAbsolutePath(), HunspellLibrary.class);			
        }
    }

    public String getLibFile() {
        return libFile;
    }

    /**
     * Calculate the filename of the native hunspell lib.
     * The files have completely different names to allow them to live
     * in the same directory and avoid confusion.
     */
    public static String libName() throws UnsupportedOperationException {
        String os = System.getProperty("os.name").toLowerCase();
        if (os.startsWith("windows")) {
            return libNameBare()+".dll";

        } else if (os.startsWith("mac os x")) {
            //	    return libNameBare()+".dylib";
            return libNameBare()+".jnilib";

        } else {
            return "lib"+libNameBare()+".so";
        }  
    }

    public static String libNameBare() throws UnsupportedOperationException {
        String os = System.getProperty("os.name").toLowerCase();
        String arch = System.getProperty("os.arch").toLowerCase();

        // Annoying that Java doesn't have consistent names for the arch types:
        boolean x86  = arch.equals("x86")    || arch.equals("i386")  || arch.equals("i686");
        boolean amd64= arch.equals("x86_64") || arch.equals("amd64") || arch.equals("ia64n");

        if (os.startsWith("windows")) {
            if (x86) {
                return "hunspell-win-x86-32";
            }
            if (amd64) { 
                return "hunspell-win-x86-64";
            }

        } else if (os.startsWith("mac os x")) {
            if (x86) {
                return "hunspell-darwin-x86-32";
            }
            if (amd64) {
                return "hunspell-darwin-x86-64";
            }
            if (arch.equals("ppc")) {		    
                return "hunspell-darwin-ppc-32";
            }

        } else if (os.startsWith("linux")) {
            if (x86) {
                return "hunspell-linux-x86-32";
            }
            if (amd64) {
                return "hunspell-linux-x86-64";
            }

        } else if (os.startsWith("sunos")) {
            //if (arch.equals("sparc")) { 
            //	return "hunspell-sunos-sparc-64";
            //}			
        }

        throw new UnsupportedOperationException("Unknown OS/arch: "+os+"/"+arch);
    }    

    /**
     * This is the cache where we keep the already loaded dictionaries around
     */
    private HashMap<String, Dictionary> map = new HashMap<String, Dictionary>();

    /**
     * Gets an instance of the dictionary. 
     *
     * @param baseFileName the base name of the dictionary, 
     * passing /dict/da_DK means that the files /dict/da_DK.dic
     * and /dict/da_DK.aff get loaded
     * @throws IOException 
     */
    public Dictionary getDictionary(String baseFileName)
            throws IOException {

        // TODO: Detect if the dictionary files have changed and reload if they have.
        if (map.containsKey(baseFileName)) {
            return map.get(baseFileName);

        } else {
            Dictionary d = new Dictionary(baseFileName);
            map.put(baseFileName, d);
            return d;
        }
    }   

    /**
     * Removes a dictionary from the internal cache
     *
     * @param baseFileName the base name of the dictionary, as passed to
     * getDictionary()
     */
    public void destroyDictionary(String baseFileName) {
        if (map.containsKey(baseFileName)) {
            map.remove(baseFileName);
        }
    }

    /**
     * Class representing a single dictionary.
     */
    public class Dictionary {
        /**
         * The pointer to the hunspell object as returned by the hunspell
         * constructor.
         */
        private Pointer hunspellDict = null;

        /**
         * The encoding used by this dictionary
         */
        private String encoding;


        private final CharsetEncoder encoder;

        /**
         * Charset decoder for hunspell.
         */
        private final CharsetDecoder decoder;
        
        /*
         * the tokenization characters
         */
        private final String wordChars;

        ByteBuffer bytes = ByteBuffer.allocate(0);

        CharBuffer charBuffer = CharBuffer.allocate(0);

        /**
         * Creates an instance of the dictionary.
         * @param baseFileName the base name of the dictionary, 
         * @throws IOException 
         */
        Dictionary(String baseFileName) throws IOException {
            File dic = new File(baseFileName + ".dic");
            File aff = new File(baseFileName + ".aff");

            if (!dic.canRead() || !aff.canRead()) {
                throw new FileNotFoundException("The dictionary files "+
                        baseFileName+
                        "(.aff|.dic) could not be read");
            }

            hunspellDict = hsl.Hunspell_create(aff.toString(), dic.toString());
            encoding = hsl.Hunspell_get_dic_encoding(hunspellDict);						               			

            //hunspell uses non-standard names of charsets 
            if ("microsoft1251".equals(encoding)) {
                encoding = "windows-1251";
            } else if ("ISCII-DEVANAGARI".equals(encoding)) {
                encoding = "ISCII91";
            }

            Charset charset = Charset.forName(encoding);
            encoder = charset.newEncoder();
            decoder = charset.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            
            wordChars = getWordCharsFromFile(aff);
        }

        /**
         * Deallocate the dictionary.
         */
        public void destroy() {
            if (hsl != null && hunspellDict != null) {
                hsl.Hunspell_destroy(hunspellDict);
                hunspellDict = null;
            }
        }

        /**
         * Used to query what are word-characters
         * @return A string composed of characters that are parts of words,
         * even if they are not alphabetic.
         */
        public String getWordChars() {
            return wordChars;
        }
        
        /**
         * Check if a word is spelled correctly
         *
         * @param word The word to check.
         */
        public boolean misspelled(String word) {
            try {
                return (hsl.Hunspell_spell(hunspellDict, stringToBytes(word)) == 0);
            } catch (UnsupportedEncodingException e) {
                return true;
            }
        }

        /**
         * Convert a Java string to a zero terminated byte array, in the
         * encoding of the dictionary, as expected by the hunspell functions.
         */
        protected byte[] stringToBytes(String str)
                throws UnsupportedEncodingException {
            bytes.clear();		     
            charBuffer.clear();

            charBuffer = BufferUtils.ensureCapacity(charBuffer, str.length() + 1);	        	        
            for (int i = 0; i < str.length(); i++) {
                char chr = str.charAt(i);
                charBuffer.put(chr);
            }
            charBuffer.put('\u0000');
            charBuffer.flip();
            final int maxCapacity = (int) (charBuffer.remaining() * encoder
                    .maxBytesPerChar());
            if (bytes.capacity() <= maxCapacity) {
                bytes = ByteBuffer.allocate(maxCapacity);
            }

            charBuffer.mark();
            encoder.reset();
            encoder.encode(charBuffer, bytes, true);
            bytes.flip();
            charBuffer.reset();
            return bytes.array();	        
        }

        /**
         * Returns a list of suggestions 
         *
         * @param word The word to check and offer suggestions for
         * @throws CharacterCodingException 
         */
        public List<String> suggest(String word) throws CharacterCodingException {
            List<String> res = new ArrayList<String>();
            try {		
                int suggestionsCount = 0;
                PointerByReference suggestions = new PointerByReference();
                suggestionsCount = hsl.Hunspell_suggest(
                        hunspellDict, suggestions, stringToBytes(word));
                if (suggestionsCount == 0) {
                    return res;
                }

                // Get each of the suggestions out of the pointer array.
                Pointer[] pointerArray = suggestions.getValue().
                        getPointerArray(0, suggestionsCount);

                for (int i=0; i<suggestionsCount; i++) {
                    long len = pointerArray[i].indexOf(0, (byte)0); 
                    if (len != -1) {
                        if (len > Integer.MAX_VALUE) {
                            throw new RuntimeException(
                                    "String improperly terminated: " + len);
                        }
                        byte[] data = pointerArray[i].getByteArray(0, (int)len);

                        ByteBuffer bb1 = ByteBuffer.allocate((int)len);
                        bb1.put(data);
                        bb1.limit((int)len);
                        bb1.flip();
                        CharBuffer ch = decoder.decode(bb1);
                        res.add(ch.toString());
                    }
                }

            } catch (UnsupportedEncodingException ex) { } // Shouldn't happen...

            return res;
        }
        
        private String getWordCharsFromFile(final File affixFile) throws IOException {
            String affixWordChars = "";
            final Scanner scanner = new Scanner(affixFile, encoding);
            try {
              while (scanner.hasNextLine()) {
                final String line = scanner.nextLine().trim();
                if (line.startsWith("WORDCHARS ")) {
                  affixWordChars = line.substring("WORDCHARS ".length());
                }
              }
            } finally {
              scanner.close();
            }
            return affixWordChars;
          }
        
        /**
         * Adds a word to the runtime dictionary.
         * @param word Word to be added.
         * @throws UnsupportedEncodingException
         */
        public void addWord(final String word) throws UnsupportedEncodingException {
            hsl.Hunspell_add(hunspellDict, stringToBytes(word));
        }

    }
}
