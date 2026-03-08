package bufpool

import "sync"

const (
	smallCap = 1500
	largeCap = 64 * 1024
)

var (
	smallPool = sync.Pool{
		New: func() any {
			buf := make([]byte, smallCap)
			return &buf
		},
	}
	largePool = sync.Pool{
		New: func() any {
			buf := make([]byte, largeCap)
			return &buf
		},
	}
)

// Borrow returns pooled buffers of 1500 or 64kb for fixed sizes.
// caller must return ownership to Return after write/pool use ends.
func Borrow(size int) []byte {
	if size <= smallCap {
		return *smallPool.Get().(*[]byte)
	}
	if size <= largeCap {
		return *largePool.Get().(*[]byte)
	}
	return make([]byte, size)
}

func Return(buf []byte) {
	if buf == nil {
		return
	}
	switch cap(buf) {
	case smallCap:
		smallPool.Put(&buf)
	case largeCap:
		largePool.Put(&buf)
	}
}
