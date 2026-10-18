#!/usr/bin/env python3
"""Packaging/navigation smoke audit for all Server Notes UI screens."""
import struct, sys, zipfile

JAR=sys.argv[1]
REQUIRED=[
 'dev/zazu/servernotes/ui/ServerNotesScreen.class',
 'dev/zazu/servernotes/ui/BaseServerScreen.class',
 'dev/zazu/servernotes/ui/NotesListScreen.class',
 'dev/zazu/servernotes/ui/NoteEditScreen.class',
 'dev/zazu/servernotes/ui/LocationsListScreen.class',
 'dev/zazu/servernotes/ui/LocationNameScreen.class',
 'dev/zazu/servernotes/ui/LocationEditScreen.class',
 'dev/zazu/servernotes/client/ZazusServerNotesClient.class',
]

def refs(data):
    p=8; count=struct.unpack_from('>H',data,p)[0]; p+=2; cp=[None]*count; i=1
    while i<count:
        tag=data[p]; p+=1
        if tag==1:
            n=struct.unpack_from('>H',data,p)[0]; p+=2
            cp[i]=(tag,data[p:p+n].decode('utf8','replace')); p+=n
        elif tag in (3,4): cp[i]=(tag,); p+=4
        elif tag in (5,6): cp[i]=(tag,); p+=8; i+=1
        elif tag in (7,8,16,19,20): cp[i]=(tag,struct.unpack_from('>H',data,p)[0]); p+=2
        elif tag in (9,10,11,12,17,18): cp[i]=(tag,*struct.unpack_from('>HH',data,p)); p+=4
        elif tag==15: cp[i]=(tag,data[p],struct.unpack_from('>H',data,p+1)[0]); p+=3
        else: raise RuntimeError(f'unsupported cp tag {tag}')
        i+=1
    def utf(i): return cp[i][1]
    def cls(i): return utf(cp[i][1])
    classes=set(); methods=[]
    for x in cp:
        if not x: continue
        if x[0]==7: classes.add(utf(x[1]))
        elif x[0] in (10,11):
            nt=cp[x[2]]; methods.append((x[0],cls(x[1]),utf(nt[1]),utf(nt[2])))
    return classes,methods

with zipfile.ZipFile(JAR) as z:
    names=set(z.namelist())
    missing=[n for n in REQUIRED if n not in names]
    if missing: raise SystemExit('Missing Server Notes UI classes: '+', '.join(missing))
    if 'dev/zazu/servernotes/ui/PlayersListScreen.class' in names:
        raise SystemExit('Obsolete PlayersListScreen is packaged')
    removed={
        'dev/zazu/servernotes/ui/TagsScreen.class',
        'dev/zazu/servernotes/ui/TagInputScreen.class',
    }
    present=sorted(removed & names)
    if present:
        raise SystemExit('Removed Server Notes UI classes are packaged: '+', '.join(present))

    parsed={n:refs(z.read(n)) for n in REQUIRED}
    literal_refs=[]
    for n,(classes,methods) in parsed.items():
        literal_refs.extend((n,m) for m in methods
                            if m[1]=='net/minecraft/network/chat/Component' and m[2]=='literal')
        bad=[m for m in methods if m[1]=='net/minecraft/network/chat/Component' and m[2]=='literal' and m[0]!=11]
        if bad: raise SystemExit(f'{n}: Component.literal is not InterfaceMethodref: {bad}')
    if not literal_refs:
        raise SystemExit('No packaged Component.literal references were found')

    edges={
      'dev/zazu/servernotes/ui/ServerNotesScreen.class': {
        'dev/zazu/servernotes/ui/NotesListScreen','dev/zazu/servernotes/ui/LocationsListScreen',
        'dev/zazu/servernotes/ui/NoteEditScreen',
        'dev/zazu/servernotes/ui/LocationNameScreen'},
      'dev/zazu/servernotes/ui/NotesListScreen.class': {'dev/zazu/servernotes/ui/NoteEditScreen'},
      'dev/zazu/servernotes/ui/LocationsListScreen.class': {'dev/zazu/servernotes/ui/LocationEditScreen'},
      'dev/zazu/servernotes/client/ZazusServerNotesClient.class': {'dev/zazu/servernotes/ui/ServerNotesScreen'},
    }
    for src,targets in edges.items():
        classes=parsed[src][0]
        absent=sorted(targets-classes)
        if absent: raise SystemExit(f'{src}: missing expected UI navigation class refs: {absent}')

print(f'Server Notes UI packaging/navigation audit passed; {len(literal_refs)}/{len(literal_refs)} Component.literal references use InterfaceMethodref.')
