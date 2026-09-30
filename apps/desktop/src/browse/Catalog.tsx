import { PosterCard } from '../components/PosterCard'
import { SectionHeader } from '../components/SectionHeader'
import { useNav, type CatalogParams } from '../nav'

/**
 * A shelf's "See all": every title the shelf held, as a poster grid under
 * the shelf's own title, source label and count. The native Halo Desktop's
 * CatalogPage, reached the same way from Home's shelves, Continue watching
 * and Search's result shelves. Posters open Detail; Back returns to the page
 * the shelf was on.
 */
export function Catalog({ title, source, items }: CatalogParams) {
  const { pop } = useNav()

  return (
    <div className="view view-col catalog-view">
      <SectionHeader title={title || 'Catalog'} onBack={pop}>
        <span className="catalog-labels">
          {source && <span className="mono">{source}</span>}
          <span className="mono">
            {items.length} {items.length === 1 ? 'ITEM' : 'ITEMS'}
          </span>
        </span>
      </SectionHeader>
      {items.length > 0 ? (
        <div className="catalog-scroll">
          <div className="catalog-grid">
            {items.map((item) => (
              <PosterCard
                key={`${item.meta.type}:${item.meta.id}`}
                meta={item.meta}
                showKind
                {...(item.metaLine ? { metaLine: item.metaLine } : {})}
              />
            ))}
          </div>
        </div>
      ) : (
        <div className="catalog-empty">
          <div className="catalog-empty-title">Nothing to show</div>
          <div className="catalog-empty-body">No titles are available in this catalog.</div>
        </div>
      )}
    </div>
  )
}
