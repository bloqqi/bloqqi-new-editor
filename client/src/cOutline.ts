// The overview of the generated C code (C view): its sections, from the
// comments the compiler writes on lines of their own (`/* Typedefs */`, ...).

export interface OutlineSection {
  /** A short title for the overview. */
  title: string;
  /** The comment's text. */
  comment: string;
  /** 1-based line of the comment. */
  line: number;
}

const SECTION = /^\/\*\s*(.+?)\s*\*\/\s*$/;

/** Shorter titles for the compiler's longest section comments. */
const SHORT_TITLES: Record<string, string> = {
  'Structs representing variables (state, input, output)': 'Variable structs',
  'Structs representing return values': 'Return value structs',
};

export function cOutline(code: string): OutlineSection[] {
  const sections: OutlineSection[] = [];
  code.split('\n').forEach((text, i) => {
    const section = SECTION.exec(text);
    // The first line says that the file is generated: not a section.
    if (section && !section[1].startsWith('FILE GENERATED')) {
      sections.push({ title: SHORT_TITLES[section[1]] ?? section[1], comment: section[1], line: i + 1 });
    }
  });
  return sections;
}
