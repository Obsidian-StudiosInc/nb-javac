#!/bin/sh

#
# Copyright (c) 2005, 2009, Oracle and/or its affiliates. All rights reserved.
# DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
#
# This code is free software; you can redistribute it and/or modify it
# under the terms of the GNU General Public License version 2 only, as
# published by the Free Software Foundation.
#
# This code is distributed in the hope that it will be useful, but WITHOUT
# ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
# FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
# version 2 for more details (a copy is included in the LICENSE file that
# accompanied this code).
#
# You should have received a copy of the GNU General Public License version
# 2 along with this work; if not, write to the Free Software Foundation,
# Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
#
# Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
# or visit www.oracle.com if you need additional information or have any
# questions.
#

# @test
# @bug 6271292
# @compile T6271292.java
# @run shell T6271292.sh
# @summary Verify that javap prints StackMapTable attribute contents
# @author Wei Tao
    
if [ "${TESTSRC}" = "" ]
then
  echo "TESTSRC not set.  Test cannot execute.  Failed."
  exit 1
fi
printf 'TESTSRC="%s"' "${TESTSRC}" >&2 ; echo >&2
if [ "${TESTJAVA}" = "" ]
then
  echo "TESTJAVA not set.  Test cannot execute.  Failed."
  exit 1
fi
printf 'TESTJAVA="%s"' "${TESTJAVA}" >&2 ; echo >&2
if [ "${TESTCLASSES}" = "" ]
then
  echo "TESTCLASSES not set.  Test cannot execute.  Failed."
  exit 1
fi
printf 'TESTCLASSES="%s"' "${TESTCLASSES}" >&2 ; echo >&2
printf 'CLASSPATH="%s"' "${CLASSPATH}" >&2 ; echo >&2

# set platform-dependent variables
OS=`uname -s`
case "$OS" in
  Windows* )
    FS="\\"
    ;;
  * )
    FS="/"
    ;;
esac

JAVAPFILE=T6271292.javap
OUTFILE=outfile

"${TESTJAVA}${FS}bin${FS}javap" ${TESTTOOLVMOPTS} -classpath "${TESTCLASSES}" -verbose T6271292 > "${JAVAPFILE}"
result="$?"
if [ "$result" -ne 0 ] 
then
  exit "$result"
fi

grep "frame_type" "${JAVAPFILE}" > "${OUTFILE}"
grep "offset_delta" "${JAVAPFILE}" >> "${OUTFILE}"
grep "stack = " "${JAVAPFILE}" >> "${OUTFILE}"
grep "locals = " "${JAVAPFILE}" >> "${OUTFILE}"
diff -w "${OUTFILE}" "${TESTSRC}${FS}T6271292.out"
result="$?"
if [ "$result" -eq 0 ]
then
  echo "Passed"
else
  echo "Failed"
fi
exit "$result"


