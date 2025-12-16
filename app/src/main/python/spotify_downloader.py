"""
Spotify2MP3 Downloader for Resonanz Android App
Downloads songs from a Spotify CSV export using yt-dlp
"""

import os
import csv
import io
import json
import re
import threading
from typing import Callable, Optional, List, Dict, Any

# Global state for cancellation and progress
_cancel_requested = False
_progress_callback = None
_current_downloads: Dict[str, Dict[str, Any]] = {}
_download_lock = threading.Lock()


def set_progress_callback(callback: Callable[[str], None]):
    """Set the callback function for progress updates (called from Kotlin)"""
    global _progress_callback
    _progress_callback = callback


def _send_progress(event_type: str, data: dict):
    """Send progress update to Kotlin via callback"""
    global _progress_callback
    if _progress_callback:
        payload = json.dumps({"type": event_type, **data})
        _progress_callback(payload)


def cancel_download():
    """Request cancellation of current download operation"""
    global _cancel_requested
    _cancel_requested = True
    _send_progress("cancelled", {"message": "Download cancelled by user"})


def reset_cancel():
    """Reset cancellation flag"""
    global _cancel_requested
    _cancel_requested = False


def parse_csv(csv_content: str) -> List[Dict[str, str]]:
    """
    Parse Spotify CSV export (from Exportify or TuneMyMusic)
    Returns list of tracks with track_name, artist_name, album_name
    """
    tracks = []
    
    try:
        # Debug: print first 500 chars of CSV
        print(f"CSV content preview: {csv_content[:500]}")
        
        reader = csv.DictReader(io.StringIO(csv_content))
        
        # Debug: print detected headers
        if reader.fieldnames:
            print(f"CSV headers detected: {reader.fieldnames}")
        
        # Map various CSV header formats to standard keys (case-insensitive, with common variations)
        header_map = {
            # Track name variations
            'track name': 'track_name',
            'track_name': 'track_name',
            'trackname': 'track_name',
            'song name': 'track_name',
            'song_name': 'track_name',
            'songname': 'track_name',
            'title': 'track_name',
            'name': 'track_name',
            'track': 'track_name',
            'song': 'track_name',
            
            # Artist name variations
            'artist name': 'artist_name',
            'artist_name': 'artist_name',
            'artistname': 'artist_name',
            'artist name(s)': 'artist_name',
            'artist names': 'artist_name',
            'artist': 'artist_name',
            'artists': 'artist_name',
            'performer': 'artist_name',
            
            # Album name variations
            'album name': 'album_name',
            'album_name': 'album_name',
            'albumname': 'album_name',
            'album': 'album_name',
        }
        
        for row in reader:
            track = {}
            for key, value in row.items():
                if key is None or value is None:
                    continue
                normalized_key = key.lower().strip()
                if normalized_key in header_map:
                    track[header_map[normalized_key]] = value.strip() if value else ''
            
            # Debug first row
            if not tracks:
                print(f"First parsed row: {track}")
            
            # Only add if we have at least track and artist
            if track.get('track_name') and track.get('artist_name'):
                tracks.append(track)
        
        print(f"Total tracks parsed: {len(tracks)}")
    
    except Exception as e:
        print(f"CSV parse error: {str(e)}")
        import traceback
        traceback.print_exc()
    
    return tracks


def sanitize_filename(name: str) -> str:
    """Remove invalid characters from filename"""
    # Remove characters not allowed in filenames
    invalid_chars = r'[<>:"/\\|?*]'
    sanitized = re.sub(invalid_chars, '', name)
    # Remove leading/trailing dots and spaces
    sanitized = sanitized.strip('. ')
    # Limit length
    return sanitized[:200] if len(sanitized) > 200 else sanitized


def search_youtube(track_name: str, artist_name: str) -> Optional[str]:
    """
    Search YouTube for a track and return the video URL
    """
    import yt_dlp
    
    search_query = f"{artist_name} - {track_name}"
    
    ydl_opts = {
        'quiet': True,
        'no_warnings': True,
        'extract_flat': True,
        'default_search': 'ytsearch1',  # Return first result
    }
    
    try:
        with yt_dlp.YoutubeDL(ydl_opts) as ydl:
            result = ydl.extract_info(f"ytsearch1:{search_query}", download=False)
            if result and 'entries' in result and result['entries']:
                return result['entries'][0].get('url') or result['entries'][0].get('id')
    except Exception as e:
        _send_progress("search_error", {
            "track": track_name,
            "artist": artist_name,
            "error": str(e)
        })
    
    return None


def download_track(
    track_name: str,
    artist_name: str,
    album_name: str,
    output_dir: str,
    track_index: int,
    total_tracks: int
) -> Optional[str]:
    """
    Download a single track from YouTube
    Returns the path to the downloaded file, or None on failure
    """
    global _cancel_requested
    import yt_dlp
    
    if _cancel_requested:
        return None
    
    _send_progress("track_start", {
        "index": track_index,
        "total": total_tracks,
        "track": track_name,
        "artist": artist_name,
        "status": "searching"
    })
    
    # Search for the track
    video_url = search_youtube(track_name, artist_name)
    if not video_url:
        _send_progress("track_failed", {
            "index": track_index,
            "track": track_name,
            "artist": artist_name,
            "error": "No YouTube result found"
        })
        return None
    
    if _cancel_requested:
        return None
    
    # Prepare filename
    safe_name = sanitize_filename(f"{artist_name} - {track_name}")
    output_template = os.path.join(output_dir, safe_name)
    
    # Progress hook for yt-dlp
    def progress_hook(d):
        if _cancel_requested:
            raise Exception("Download cancelled")
        
        if d['status'] == 'downloading':
            percent = d.get('_percent_str', '0%').strip()
            speed = d.get('_speed_str', 'N/A')
            _send_progress("track_progress", {
                "index": track_index,
                "track": track_name,
                "artist": artist_name,
                "percent": percent,
                "speed": speed,
                "status": "downloading"
            })
        elif d['status'] == 'finished':
            _send_progress("track_progress", {
                "index": track_index,
                "track": track_name,
                "artist": artist_name,
                "status": "finishing"
            })
    
    # yt-dlp options - download best audio as m4a
    # Use format 140 which is m4a audio, with fallbacks
    ydl_opts = {
        'format': '140/bestaudio[ext=m4a]/bestaudio/best',
        'outtmpl': f'{output_template}.%(ext)s',  # Include extension placeholder
        'quiet': False,  # Show output for debugging
        'no_warnings': False,
        'progress_hooks': [progress_hook],
        'overwrites': True,  # Overwrite if exists
        'writethumbnail': False,  # Don't write thumbnail to file
        'noplaylist': True,  # Single video only
    }
    
    try:
        _send_progress("track_progress", {
            "index": track_index,
            "track": track_name,
            "artist": artist_name,
            "status": "downloading"
        })
        
        import glob
        
        # List files before download
        before_files = set(glob.glob(os.path.join(output_dir, "*")))
        print(f"Files before download: {len(before_files)}")
        
        downloaded_file = None
        info = None
        
        with yt_dlp.YoutubeDL(ydl_opts) as ydl:
            # Build full URL if we only got an ID
            if not video_url.startswith('http'):
                video_url = f"https://www.youtube.com/watch?v={video_url}"
            
            info = ydl.extract_info(video_url, download=True)
        
        # List files after download to find new file
        after_files = set(glob.glob(os.path.join(output_dir, "*")))
        new_files = after_files - before_files
        print(f"Files after download: {len(after_files)}, New files: {new_files}")
        
        # Method 1: Check for new files
        if new_files:
            downloaded_file = list(new_files)[0]
            print(f"Found new file: {downloaded_file}")
        
        # Method 2: Try info dict
        if not downloaded_file and info:
            print(f"Info dict keys: {list(info.keys()) if info else 'None'}")
            
            # Try _filename first
            downloaded_file = info.get('_filename')
            print(f"_filename from info: {downloaded_file}")
            
            # Try requested_downloads
            if not downloaded_file:
                requested = info.get('requested_downloads', [])
                if requested:
                    downloaded_file = requested[0].get('filepath')
                    print(f"filepath from requested_downloads: {downloaded_file}")
            
            # Fallback: construct from info
            if not downloaded_file:
                ext = info.get('ext', 'm4a')
                downloaded_file = f"{output_template}.{ext}"
                print(f"Constructed path: {downloaded_file}")
        
        # Method 3: Search by pattern
        if not downloaded_file or not os.path.exists(downloaded_file):
            print(f"Searching for file with pattern: {output_template}*")
            pattern = f"{output_template}*"
            matches = glob.glob(pattern)
            print(f"Glob matches: {matches}")
            
            # Filter to audio files only
            audio_exts = ['.m4a', '.mp3', '.webm', '.opus', '.ogg', '.mp4', '.aac']
            audio_matches = [m for m in matches if any(m.endswith(ext) for ext in audio_exts)]
            print(f"Audio matches: {audio_matches}")
            
            if audio_matches:
                downloaded_file = audio_matches[0]
            elif matches:
                downloaded_file = matches[0]
        
        # Method 4: Direct extension check
        if not downloaded_file or not os.path.exists(downloaded_file):
            print(f"Direct check for common extensions...")
            for ext in ['.m4a', '.webm', '.opus', '.mp3', '.ogg', '.mp4']:
                test_path = output_template + ext
                if os.path.exists(test_path):
                    downloaded_file = test_path
                    print(f"Found at: {downloaded_file}")
                    break
        
        # Final verification
        output_path = downloaded_file
        if not output_path or not os.path.exists(output_path):
            print(f"ERROR: Output file not found after download!")
            print(f"Expected base: {output_template}")
            print(f"Tried path: {output_path}")
            # List all files in output dir for debugging
            all_files = os.listdir(output_dir) if os.path.exists(output_dir) else []
            print(f"All files in output dir ({len(all_files)}): {all_files[:10]}...")
            return None
        
        print(f"SUCCESS: Downloaded to {output_path}")
        
        # Get thumbnail URL from YouTube info
        thumbnail_url = None
        if info:
            # Try to get the best thumbnail
            thumbnails = info.get('thumbnails', [])
            if thumbnails:
                # Get highest resolution thumbnail
                best_thumb = max(thumbnails, key=lambda x: x.get('height', 0) * x.get('width', 0))
                thumbnail_url = best_thumb.get('url')
            if not thumbnail_url:
                thumbnail_url = info.get('thumbnail')
            print(f"Thumbnail URL: {thumbnail_url}")
        
        # Set ID3/metadata tags using mutagen
        try:
            set_id3_tags(output_path, track_name, artist_name, album_name, track_index, thumbnail_url)
        except Exception as tag_error:
            print(f"Warning: Could not set tags: {str(tag_error)}")
            import traceback
            traceback.print_exc()
        
        _send_progress("track_complete", {
            "index": track_index,
            "total": total_tracks,
            "track": track_name,
            "artist": artist_name,
            "path": output_path
        })
        
        return output_path
        
    except Exception as e:
        error_msg = str(e)
        if "cancelled" in error_msg.lower():
            return None
        
        _send_progress("track_failed", {
            "index": track_index,
            "track": track_name,
            "artist": artist_name,
            "error": error_msg
        })
        return None


def set_id3_tags(file_path: str, title: str, artist: str, album: str, track_num: int, thumbnail_url: str = None):
    """Set ID3 tags on the audio file using mutagen"""
    print(f"Setting tags for: {file_path}")
    print(f"  Title: {title}, Artist: {artist}, Album: {album}")
    
    # Download thumbnail if available
    thumbnail_data = None
    if thumbnail_url:
        try:
            import urllib.request
            with urllib.request.urlopen(thumbnail_url, timeout=10) as response:
                thumbnail_data = response.read()
                print(f"  Downloaded thumbnail: {len(thumbnail_data)} bytes")
        except Exception as thumb_err:
            print(f"  Could not download thumbnail: {thumb_err}")
    
    try:
        if file_path.endswith('.mp3'):
            from mutagen.mp3 import MP3
            from mutagen.id3 import ID3, TIT2, TPE1, TALB, TRCK, APIC
            try:
                audio = MP3(file_path, ID3=ID3)
            except Exception:
                audio = MP3(file_path)
                audio.add_tags()
            
            audio.tags['TIT2'] = TIT2(encoding=3, text=title)
            audio.tags['TPE1'] = TPE1(encoding=3, text=artist)
            audio.tags['TALB'] = TALB(encoding=3, text=album or 'Unknown Album')
            audio.tags['TRCK'] = TRCK(encoding=3, text=str(track_num))
            
            if thumbnail_data:
                audio.tags['APIC'] = APIC(
                    encoding=3,
                    mime='image/jpeg',
                    type=3,  # Cover (front)
                    desc='Cover',
                    data=thumbnail_data
                )
            
            audio.save()
            print(f"  MP3 tags saved successfully")
            
        elif file_path.endswith('.m4a') or file_path.endswith('.mp4'):
            from mutagen.mp4 import MP4, MP4Cover
            audio = MP4(file_path)
            
            # M4A tags must be lists
            audio['\xa9nam'] = [title]  # Title
            audio['\xa9ART'] = [artist]  # Artist
            audio['\xa9alb'] = [album or 'Unknown Album']  # Album
            audio['trkn'] = [(track_num, 0)]  # Track number
            
            if thumbnail_data:
                # Determine if JPEG or PNG
                if thumbnail_data[:3] == b'\xff\xd8\xff':
                    cover = MP4Cover(thumbnail_data, imageformat=MP4Cover.FORMAT_JPEG)
                else:
                    cover = MP4Cover(thumbnail_data, imageformat=MP4Cover.FORMAT_PNG)
                audio['covr'] = [cover]
            
            audio.save()
            print(f"  M4A tags saved successfully")
            
        elif file_path.endswith('.opus'):
            from mutagen.oggopus import OggOpus
            audio = OggOpus(file_path)
            audio['title'] = [title]
            audio['artist'] = [artist]
            audio['album'] = [album or 'Unknown Album']
            audio['tracknumber'] = [str(track_num)]
            audio.save()
            print(f"  Opus tags saved successfully")
            
        elif file_path.endswith('.ogg'):
            from mutagen.oggvorbis import OggVorbis
            audio = OggVorbis(file_path)
            audio['title'] = [title]
            audio['artist'] = [artist]
            audio['album'] = [album or 'Unknown Album']
            audio['tracknumber'] = [str(track_num)]
            audio.save()
            print(f"  Ogg tags saved successfully")
                
        elif file_path.endswith('.webm'):
            # WebM audio tagging is limited
            print(f"  WebM format - limited tagging support")
            
    except Exception as e:
        print(f"  ERROR setting tags: {str(e)}")
        import traceback
        traceback.print_exc()


def download_playlist(
    csv_content: str,
    output_dir: str,
    skip_instrumentals: bool = False
) -> Dict[str, Any]:
    """
    Main function to download a playlist from CSV
    Called from Kotlin via Chaquopy
    
    Returns dict with:
        - success: bool
        - total: int
        - downloaded: int
        - failed: int
        - failed_tracks: list of failed track info
        - output_files: list of downloaded file paths
    """
    global _cancel_requested
    reset_cancel()
    
    result = {
        "success": False,
        "total": 0,
        "downloaded": 0,
        "failed": 0,
        "failed_tracks": [],
        "output_files": []
    }
    
    # Parse CSV
    tracks = parse_csv(csv_content)
    if not tracks:
        _send_progress("error", {"message": "No valid tracks found in CSV"})
        return result
    
    result["total"] = len(tracks)
    
    _send_progress("playlist_start", {
        "total": len(tracks),
        "output_dir": output_dir
    })
    
    # Ensure output directory exists
    os.makedirs(output_dir, exist_ok=True)
    
    # Download each track
    for i, track in enumerate(tracks):
        if _cancel_requested:
            _send_progress("cancelled", {
                "downloaded": result["downloaded"],
                "remaining": len(tracks) - i
            })
            break
        
        track_name = track.get('track_name', '')
        artist_name = track.get('artist_name', '')
        album_name = track.get('album_name', '')
        
        # Skip instrumentals if requested
        if skip_instrumentals:
            lower_name = track_name.lower()
            if 'instrumental' in lower_name or 'karaoke' in lower_name:
                _send_progress("track_skipped", {
                    "index": i + 1,
                    "track": track_name,
                    "reason": "Instrumental version"
                })
                continue
        
        output_path = download_track(
            track_name=track_name,
            artist_name=artist_name,
            album_name=album_name,
            output_dir=output_dir,
            track_index=i + 1,
            total_tracks=len(tracks)
        )
        
        if output_path:
            result["downloaded"] += 1
            result["output_files"].append(output_path)
        else:
            result["failed"] += 1
            result["failed_tracks"].append({
                "track": track_name,
                "artist": artist_name
            })
    
    result["success"] = result["downloaded"] > 0
    
    _send_progress("playlist_complete", {
        "total": result["total"],
        "downloaded": result["downloaded"],
        "failed": result["failed"]
    })
    
    return result


def generate_m3u(output_files: List[str], playlist_name: str, output_dir: str) -> Optional[str]:
    """Generate an M3U playlist file"""
    if not output_files:
        return None
    
    m3u_path = os.path.join(output_dir, f"{sanitize_filename(playlist_name)}.m3u")
    
    try:
        with open(m3u_path, 'w', encoding='utf-8') as f:
            f.write('#EXTM3U\n')
            for file_path in output_files:
                # Use relative paths
                filename = os.path.basename(file_path)
                f.write(f'{filename}\n')
        
        _send_progress("m3u_created", {"path": m3u_path})
        return m3u_path
    
    except Exception as e:
        _send_progress("warning", {"message": f"Could not create M3U: {str(e)}"})
        return None


# Test function for verifying the module works
def test_connection() -> str:
    """Test that Python module is working"""
    try:
        import yt_dlp
        return f"Spotify Downloader OK, yt-dlp version: {yt_dlp.version.__version__}"
    except Exception as e:
        return f"Module loaded but yt-dlp error: {str(e)}"


def get_tracks_from_csv(csv_content: str) -> List[Dict[str, str]]:
    """Parse CSV and return list of tracks (called from Kotlin)"""
    return parse_csv(csv_content)


def download_single_track(
    track_name: str,
    artist_name: str,
    album_name: str,
    output_dir: str,
    track_index: int
) -> Dict[str, Any]:
    """
    Download a single track (called from Kotlin for each track)
    Returns result dict with success, path, or error
    """
    try:
        output_path = download_track(
            track_name=track_name,
            artist_name=artist_name,
            album_name=album_name,
            output_dir=output_dir,
            track_index=track_index,
            total_tracks=1
        )
        
        if output_path:
            return {
                "success": True,
                "path": output_path
            }
        else:
            return {
                "success": False,
                "error": "Download failed - no output file"
            }
    except Exception as e:
        return {
            "success": False,
            "error": str(e)
        }

